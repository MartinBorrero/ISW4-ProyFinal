param(
    [string]$EnvFile = "v3-distributed/deploy.env",
    [switch]$SkipBuild,
    [switch]$SkipClient,
    [switch]$SkipVisualization,
    [switch]$SkipMaster,
    [switch]$SkipPartitionGeneration,
    [switch]$SkipWorkers,
    [switch]$SkipPartitionDistribution,
    [switch]$SkipPermissions,
    [int]$StartWorker = 1,
    [int]$EndWorker = 0,
    [string]$ProxyJumpHost = ""
)

$ErrorActionPreference = "Stop"

function Invoke-Checked {
    param(
        [scriptblock]$Command,
        [string]$Description
    )

    Write-Host ">> $Description"
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $Description"
    }
}

$script:LastReachableSshHost = $null
if (-not [string]::IsNullOrWhiteSpace($ProxyJumpHost)) {
    $script:LastReachableSshHost = $ProxyJumpHost
}

function Invoke-External {
    param([scriptblock]$ScriptBlock)

    & $ScriptBlock
    return $LASTEXITCODE -eq 0
}

function Invoke-RemoteChecked {
    param(
        [string]$SshHost,
        [string]$InternalHost,
        [string]$RemoteCommand,
        [string]$Description
    )

    Write-Host ">> $Description on $SshHost"
    if (Invoke-External { & ssh "${sshUser}@${SshHost}" $RemoteCommand }) {
        $script:LastReachableSshHost = $SshHost
        return
    }

    if (-not [string]::IsNullOrWhiteSpace($script:LastReachableSshHost) -and $script:LastReachableSshHost -ne $SshHost) {
        Write-Host "Direct SSH to $SshHost failed. Retrying through $script:LastReachableSshHost to internal host $InternalHost..."
        if (Invoke-External { & ssh -J "${sshUser}@${script:LastReachableSshHost}" "${sshUser}@${InternalHost}" $RemoteCommand }) {
            return
        }
    }

    throw "Command failed: $Description"
}

function Copy-ToRemoteChecked {
    param(
        [string[]]$Sources,
        [string]$SshHost,
        [string]$InternalHost,
        [string]$RemotePath,
        [string]$Description
    )

    Write-Host ">> $Description to $SshHost"
    $directDestination = "${sshUser}@${SshHost}:${RemotePath}"
    if (Invoke-External { & scp @Sources $directDestination }) {
        $script:LastReachableSshHost = $SshHost
        return
    }

    if (-not [string]::IsNullOrWhiteSpace($script:LastReachableSshHost) -and $script:LastReachableSshHost -ne $SshHost) {
        Write-Host "Direct SCP to $SshHost failed. Retrying through $script:LastReachableSshHost to internal host $InternalHost..."
        $jumpDestination = "${sshUser}@${InternalHost}:${RemotePath}"
        if (Invoke-External { & scp -o "ProxyJump=${sshUser}@${script:LastReachableSshHost}" @Sources $jumpDestination }) {
            return
        }
    }

    throw "Command failed: $Description"
}

function Copy-FromRemoteChecked {
    param(
        [string]$SshHost,
        [string]$InternalHost,
        [string]$RemotePath,
        [string]$LocalPath,
        [string]$Description
    )

    Write-Host ">> $Description from $SshHost"
    if (Invoke-External { & scp -r "${sshUser}@${SshHost}:${RemotePath}" $LocalPath }) {
        $script:LastReachableSshHost = $SshHost
        return
    }

    if (-not [string]::IsNullOrWhiteSpace($script:LastReachableSshHost) -and $script:LastReachableSshHost -ne $SshHost) {
        Write-Host "Direct SCP from $SshHost failed. Retrying through $script:LastReachableSshHost to internal host $InternalHost..."
        if (Invoke-External { & scp -r -o "ProxyJump=${sshUser}@${script:LastReachableSshHost}" "${sshUser}@${InternalHost}:${RemotePath}" $LocalPath }) {
            return
        }
    }

    throw "Command failed: $Description"
}

function Read-DeployEnv {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        throw "Missing deployment env file: $Path"
    }

    $config = @{}
    $insideWorkers = $false

    foreach ($rawLine in Get-Content -Path $Path) {
        $line = $rawLine.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith("#")) {
            continue
        }

        if ($line -match "^WORKER_HOSTS=\(") {
            $insideWorkers = $true
            $activeList = "WORKER_HOSTS"
            if (-not $config.ContainsKey($activeList)) {
                $config[$activeList] = @()
            }
            continue
        }

        if ($line -match "^SSH_WORKER_HOSTS=\(") {
            $insideWorkers = $true
            $activeList = "SSH_WORKER_HOSTS"
            if (-not $config.ContainsKey($activeList)) {
                $config[$activeList] = @()
            }
            continue
        }

        if ($insideWorkers) {
            if ($line -eq ")") {
                $insideWorkers = $false
                continue
            }
            $config[$activeList] += $line
            continue
        }

        if ($line -match "^([^=]+)=(.*)$") {
            $config[$matches[1]] = $matches[2]
        }
    }

    if (-not $config.ContainsKey("WORKER_HOSTS")) {
        $config["WORKER_HOSTS"] = @()
    }
    return $config
}

function Require-Value {
    param([hashtable]$Config, [string]$Key)

    if (-not $Config.ContainsKey($Key) -or [string]::IsNullOrWhiteSpace($Config[$Key])) {
        throw "$Key is required in deploy.env"
    }

    return $Config[$Key]
}

function Require-Path {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        throw "Required path not found: $Path"
    }
}

function Get-PartitionCount {
    param([hashtable]$Config)

    if (-not $Config.ContainsKey("WORKER_HOSTS") -or @($Config["WORKER_HOSTS"]).Count -eq 0) {
        throw "WORKER_HOSTS must contain at least one worker; partition count is derived from worker count"
    }

    return @($Config["WORKER_HOSTS"]).Count
}

function Copy-Scripts {
    param(
        [string]$HostName,
        [string]$InternalHost,
        [string]$RemoteRoot,
        [string[]]$Scripts,
        [string[]]$ExtraFiles = @(),
        [string]$RuntimeEnvFile = ""
    )

    $items = @()
    $items += $Scripts
    $items += $ExtraFiles
    $items += "v3-distributed/start-node.sh"
    if (-not [string]::IsNullOrWhiteSpace($RuntimeEnvFile)) {
        $items += $RuntimeEnvFile
    }
    Copy-ToRemoteChecked -Sources $items -SshHost $HostName -InternalHost $InternalHost -RemotePath "${RemoteRoot}/v3-distributed/" -Description "Copy scripts to $HostName"
}

function Write-LfFile {
    param([string]$Path, [string[]]$Lines)

    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Resolve-Path -LiteralPath $parent).Path + [System.IO.Path]::DirectorySeparatorChar + (Split-Path -Leaf $Path), (($Lines -join "`n") + "`n"), $encoding)
}

function Format-BashArray {
    param([string]$Name, [string[]]$Values)

    $lines = @("$Name=(")
    foreach ($value in $Values) {
        $lines += "  $value"
    }
    $lines += ")"
    return $lines
}

function New-RuntimeEnvFile {
    param(
        [string]$Role,
        [hashtable]$Config
    )

    $baseDir = Join-Path "build/tmp/deploy-env" $Role
    $fileName = if ($Role -eq "client") { "client.env" } else { "deploy.env" }
    $path = Join-Path $baseDir $fileName
    $lines = @()

    switch ($Role) {
        "client" {
            $lines += "BROKER_HOST=$brokerHost"
            $lines += "BROKER_PORT=$($config["BROKER_PORT"])"
            $lines += "MAX_ROWS=$($config["MAX_ROWS"])"
            $lines += "CLIENT_OUTPUT_PATH=$($config["CLIENT_OUTPUT_PATH"])"
            $lines += ""
            $lines += Format-BashArray -Name "WORKER_HOSTS" -Values $workerHosts
        }
        "visualization" {
            $lines += "VISUALIZATION_HOST=$visualizationHost"
            $lines += "VISUALIZATION_PORT=$($config["VISUALIZATION_PORT"])"
        }
        "coordination" {
            $lines += "SSH_USER=$sshUser"
            $lines += "REMOTE_ROOT=$remoteRoot"
            $lines += "MASTER_HOST=$masterHost"
            $lines += "BROKER_HOST=$brokerHost"
            $lines += "VISUALIZATION_HOST=$visualizationHost"
            $lines += "MASTER_PORT=$($config["MASTER_PORT"])"
            $lines += "BROKER_PORT=$($config["BROKER_PORT"])"
            $lines += "VISUALIZATION_PORT=$($config["VISUALIZATION_PORT"])"
            $lines += ""
            $lines += Format-BashArray -Name "WORKER_HOSTS" -Values $workerHosts
        }
        "worker" {
            $lines += "MASTER_HOST=$masterHost"
            $lines += "VISUALIZATION_HOST=$visualizationHost"
            $lines += "MASTER_PORT=$($config["MASTER_PORT"])"
            $lines += "VISUALIZATION_PORT=$($config["VISUALIZATION_PORT"])"
            $lines += "WORKER_BASE_PORT=$($config["WORKER_BASE_PORT"])"
            $lines += "WORKER_HEAP=$($config["WORKER_HEAP"])"
            $lines += ""
            $lines += Format-BashArray -Name "WORKER_HOSTS" -Values $workerHosts
        }
        default {
            throw "Unsupported runtime env role: $Role"
        }
    }

    Write-LfFile -Path $path -Lines $lines
    return $path
}

$config = Read-DeployEnv -Path $EnvFile

$sshUser = Require-Value -Config $config -Key "SSH_USER"
$remoteRoot = Require-Value -Config $config -Key "REMOTE_ROOT"
$clientHost = if ($config.ContainsKey("CLIENT_HOST")) { $config["CLIENT_HOST"] } else { Require-Value -Config $config -Key "VISUALIZATION_HOST" }
$visualizationHost = Require-Value -Config $config -Key "VISUALIZATION_HOST"
$masterHost = Require-Value -Config $config -Key "MASTER_HOST"
$brokerHost = if ($config.ContainsKey("BROKER_HOST")) { $config["BROKER_HOST"] } else { $masterHost }
$sshClientHost = if ($config.ContainsKey("SSH_CLIENT_HOST")) { $config["SSH_CLIENT_HOST"] } else { $clientHost }
$sshVisualizationHost = if ($config.ContainsKey("SSH_VISUALIZATION_HOST")) { $config["SSH_VISUALIZATION_HOST"] } else { $visualizationHost }
$sshMasterHost = if ($config.ContainsKey("SSH_MASTER_HOST")) { $config["SSH_MASTER_HOST"] } else { $masterHost }
$workerHosts = @($config["WORKER_HOSTS"])
$partitions = Get-PartitionCount -Config $config
$sshWorkerHosts = if ($config.ContainsKey("SSH_WORKER_HOSTS")) { @($config["SSH_WORKER_HOSTS"]) } else { $workerHosts }
$config["BROKER_PORT"] = if ($config.ContainsKey("BROKER_PORT")) { $config["BROKER_PORT"] } else { "10000" }
$config["MASTER_PORT"] = if ($config.ContainsKey("MASTER_PORT")) { $config["MASTER_PORT"] } else { "10001" }
$config["VISUALIZATION_PORT"] = if ($config.ContainsKey("VISUALIZATION_PORT")) { $config["VISUALIZATION_PORT"] } else { "10003" }
$config["WORKER_BASE_PORT"] = if ($config.ContainsKey("WORKER_BASE_PORT")) { $config["WORKER_BASE_PORT"] } else { "10010" }
$config["WORKER_HEAP"] = if ($config.ContainsKey("WORKER_HEAP")) { $config["WORKER_HEAP"] } else { "1024m" }
$config["MAX_ROWS"] = if ($config.ContainsKey("MAX_ROWS")) { $config["MAX_ROWS"] } else { "0" }
$config["CLIENT_OUTPUT_PATH"] = if ($config.ContainsKey("CLIENT_OUTPUT_PATH")) { $config["CLIENT_OUTPUT_PATH"] } else { "output/resultados-v3.csv" }
$generateAndDistributePartitions = if ($config.ContainsKey("GENERATE_AND_DISTRIBUTE_PARTITIONS")) { $config["GENERATE_AND_DISTRIBUTE_PARTITIONS"].ToLowerInvariant() -ne "false" } else { $true }
$copyFullDatagramToMaster = if ($config.ContainsKey("COPY_FULL_DATAGRAM_TO_MASTER")) { $config["COPY_FULL_DATAGRAM_TO_MASTER"].ToLowerInvariant() -ne "false" } else { $true }
$partitionDistributionMode = if ($config.ContainsKey("PARTITION_DISTRIBUTION_MODE")) { $config["PARTITION_DISTRIBUTION_MODE"].ToLowerInvariant() } else { "master" }

if ($workerHosts.Count -lt $partitions) {
    throw "WORKER_HOSTS has $($workerHosts.Count) entries, but PARTITIONS=$partitions"
}

if ($sshWorkerHosts.Count -lt $partitions) {
    throw "SSH_WORKER_HOSTS has $($sshWorkerHosts.Count) entries, but PARTITIONS=$partitions"
}

if ($StartWorker -lt 1 -or $StartWorker -gt $partitions) {
    throw "StartWorker must be between 1 and $partitions"
}

if ($EndWorker -eq 0) {
    $EndWorker = $partitions
}

if ($EndWorker -lt $StartWorker -or $EndWorker -gt $partitions) {
    throw "EndWorker must be between StartWorker and $partitions"
}

if ($generateAndDistributePartitions -and -not $SkipPartitionGeneration) {
    Require-Path "data/lines-241-ActiveGT.csv"
    if ($copyFullDatagramToMaster) {
        Require-Path "data/datagrams4Pilot.csv"
    }
}
Require-Path "v3-distributed/deploy.env"
Require-Path "gradlew.bat"

$clientRuntimeEnv = New-RuntimeEnvFile -Role "client" -Config $config
$visualizationRuntimeEnv = New-RuntimeEnvFile -Role "visualization" -Config $config
$coordinationRuntimeEnv = New-RuntimeEnvFile -Role "coordination" -Config $config
$workerRuntimeEnv = New-RuntimeEnvFile -Role "worker" -Config $config

if ($SkipBuild) {
    Write-Host "Skipping Gradle build because -SkipBuild was provided."
} else {
    Write-Host "Building runtime distributions from Windows..."
    Invoke-Checked { & .\gradlew.bat `
            "clean" `
            ":v3-distributed:visualization:installDist" `
            ":v3-distributed:client:installDist" `
            ":v3-distributed:master:installDist" `
            ":v3-distributed:broker:installDist" `
            ":v3-distributed:worker:installDist" `
            ":v3-distributed:partitioner:installDist" } "Gradle installDist"
}

if ($SkipClient) {
    Write-Host "Skipping Client deployment because -SkipClient was provided."
} else {
    Write-Host "Deploying Client to $sshClientHost"
    Invoke-RemoteChecked -SshHost $sshClientHost -InternalHost $clientHost -RemoteCommand "mkdir -p '${remoteRoot}/v3-distributed/client' '${remoteRoot}/data'" -Description "Create client directories"
    Copy-ToRemoteChecked -Sources @("-r", "v3-distributed/client/build") -SshHost $sshClientHost -InternalHost $clientHost -RemotePath "${remoteRoot}/v3-distributed/client/" -Description "Copy client"
    Copy-Scripts -HostName $sshClientHost -InternalHost $clientHost -RemoteRoot $remoteRoot -Scripts @("v3-distributed/start-client.sh") -RuntimeEnvFile $clientRuntimeEnv
}

if ($SkipVisualization) {
    Write-Host "Skipping Visualization deployment because -SkipVisualization was provided."
} else {
    Write-Host "Deploying Visualization to $sshVisualizationHost"
    Invoke-RemoteChecked -SshHost $sshVisualizationHost -InternalHost $visualizationHost -RemoteCommand "mkdir -p '${remoteRoot}/v3-distributed/visualization' '${remoteRoot}/data'" -Description "Create visualization directories"
    Copy-ToRemoteChecked -Sources @("-r", "v3-distributed/visualization/build") -SshHost $sshVisualizationHost -InternalHost $visualizationHost -RemotePath "${remoteRoot}/v3-distributed/visualization/" -Description "Copy visualization"
    Copy-Scripts -HostName $sshVisualizationHost -InternalHost $visualizationHost -RemoteRoot $remoteRoot -Scripts @("v3-distributed/start-visualization.sh") -RuntimeEnvFile $visualizationRuntimeEnv
}

if ($SkipMaster) {
    Write-Host "Skipping Master/Broker deployment because -SkipMaster was provided."
} else {
    Write-Host "Deploying PC2 Master + Broker to $sshMasterHost"
    Invoke-RemoteChecked -SshHost $sshMasterHost -InternalHost $masterHost -RemoteCommand "mkdir -p '${remoteRoot}/v3-distributed/master' '${remoteRoot}/v3-distributed/broker' '${remoteRoot}/v3-distributed/partitioner' '${remoteRoot}/data'" -Description "Create PC2 directories"
    Copy-ToRemoteChecked -Sources @("-r", "v3-distributed/master/build") -SshHost $sshMasterHost -InternalHost $masterHost -RemotePath "${remoteRoot}/v3-distributed/master/" -Description "Copy master to PC2"
    Copy-ToRemoteChecked -Sources @("-r", "v3-distributed/broker/build") -SshHost $sshMasterHost -InternalHost $masterHost -RemotePath "${remoteRoot}/v3-distributed/broker/" -Description "Copy broker to PC2"
    Copy-ToRemoteChecked -Sources @("-r", "v3-distributed/partitioner/build") -SshHost $sshMasterHost -InternalHost $masterHost -RemotePath "${remoteRoot}/v3-distributed/partitioner/" -Description "Copy partitioner to PC2"
    Copy-Scripts -HostName $sshMasterHost -InternalHost $masterHost -RemoteRoot $remoteRoot -Scripts @("v3-distributed/start-master.sh", "v3-distributed/start-broker.sh") -RuntimeEnvFile $coordinationRuntimeEnv
    Copy-ToRemoteChecked -Sources @("v3-distributed/distribute-partitions-from-master.sh") -SshHost $sshMasterHost -InternalHost $masterHost -RemotePath "${remoteRoot}/v3-distributed/" -Description "Copy master partition distributor"
}

if ($generateAndDistributePartitions -and $SkipPartitionGeneration) {
    Write-Host "Skipping partition generation because -SkipPartitionGeneration was provided."
} elseif ($generateAndDistributePartitions) {
    Write-Host "Copying full datagram only to PC2 and generating partitions there"
    if ($copyFullDatagramToMaster) {
        Copy-ToRemoteChecked -Sources @("data/datagrams4Pilot.csv") -SshHost $sshMasterHost -InternalHost $masterHost -RemotePath "${remoteRoot}/data/" -Description "Copy full datagram to PC2"
    } else {
        Write-Host "Skipping full datagram copy because COPY_FULL_DATAGRAM_TO_MASTER=false"
        Invoke-RemoteChecked -SshHost $sshMasterHost -InternalHost $masterHost -RemoteCommand "test -f '${remoteRoot}/data/datagrams4Pilot.csv'" -Description "Verify full datagram exists on PC2"
    }
    Copy-ToRemoteChecked -Sources @("data/lines-241-ActiveGT.csv") -SshHost $sshMasterHost -InternalHost $masterHost -RemotePath "${remoteRoot}/data/" -Description "Copy routes file to PC2"
    Invoke-RemoteChecked -SshHost $sshMasterHost -InternalHost $masterHost -RemoteCommand "cd '${remoteRoot}' && echo 'Generating partitions on PC2. This can take several minutes...' && chmod +x v3-distributed/partitioner/build/install/partitioner/bin/partitioner && v3-distributed/partitioner/build/install/partitioner/bin/partitioner --datagrams data/datagrams4Pilot.csv --routes data/lines-241-ActiveGT.csv --output data/partitions-${partitions} --partitions ${partitions} && echo 'Partition generation finished on PC2.'" -Description "Generate partitions on PC2"
}

if ($SkipWorkers) {
    Write-Host "Skipping Worker deployment because -SkipWorkers was provided."
} else {
    for ($i = $StartWorker; $i -le $EndWorker; $i++) {
        $hostName = $sshWorkerHosts[$i - 1]
        $internalHostName = $workerHosts[$i - 1]
        Write-Host "Deploying Worker $i to $hostName"
        Invoke-RemoteChecked -SshHost $hostName -InternalHost $internalHostName -RemoteCommand "mkdir -p '${remoteRoot}/v3-distributed/worker' '${remoteRoot}/data'" -Description "Create Worker $i directories"
        Copy-ToRemoteChecked -Sources @("-r", "v3-distributed/worker/build") -SshHost $hostName -InternalHost $internalHostName -RemotePath "${remoteRoot}/v3-distributed/worker/" -Description "Copy worker build to Worker $i"
        Copy-Scripts -HostName $hostName -InternalHost $internalHostName -RemoteRoot $remoteRoot -Scripts @("v3-distributed/start-worker.sh") -RuntimeEnvFile $workerRuntimeEnv
    }
}

if ($SkipPartitionDistribution) {
    Write-Host "Skipping partition distribution because -SkipPartitionDistribution was provided."
} elseif ($generateAndDistributePartitions) {
    if ($partitionDistributionMode -eq "windows") {
        Write-Host "Copying generated partitions from PC2 to Windows for emergency distribution"
        $localPartitionRoot = "build\tmp\deploy-partitions"
        $localPartitionsDir = Join-Path $localPartitionRoot "partitions-${partitions}"
        if (Test-Path $localPartitionRoot) {
            Remove-Item -LiteralPath $localPartitionRoot -Recurse -Force
        }
        New-Item -ItemType Directory -Force -Path $localPartitionRoot | Out-Null
        Copy-FromRemoteChecked -SshHost $sshMasterHost -InternalHost $masterHost -RemotePath "${remoteRoot}/data/partitions-${partitions}" -LocalPath $localPartitionRoot -Description "Copy generated partitions from PC2"

        Write-Host "Distributing partitions from Windows to workers"

        for ($i = $StartWorker; $i -le $EndWorker; $i++) {
            $hostName = $sshWorkerHosts[$i - 1]
            $internalHostName = $workerHosts[$i - 1]
            $partitionIndex = $i - 1
            $partitionFile = Join-Path $localPartitionsDir "partition-${partitionIndex}.csv"
            Require-Path $partitionFile
            Invoke-RemoteChecked -SshHost $hostName -InternalHost $internalHostName -RemoteCommand "mkdir -p '${remoteRoot}/data/partitions-${partitions}' '${remoteRoot}/data'" -Description "Create data directories on Worker $i"
            Copy-ToRemoteChecked -Sources @($partitionFile) -SshHost $hostName -InternalHost $internalHostName -RemotePath "${remoteRoot}/data/partitions-${partitions}/" -Description "Copy partition-$partitionIndex to Worker $i"
            Copy-ToRemoteChecked -Sources @("data/lines-241-ActiveGT.csv") -SshHost $hostName -InternalHost $internalHostName -RemotePath "${remoteRoot}/data/" -Description "Copy routes file to Worker $i"
            Invoke-RemoteChecked -SshHost $hostName -InternalHost $internalHostName -RemoteCommand "test -f '${remoteRoot}/data/partitions-${partitions}/partition-${partitionIndex}.csv'" -Description "Verify partition-$partitionIndex exists on Worker $i"
        }
    } elseif ($partitionDistributionMode -eq "master") {
        Write-Host "Distributing partitions from PC2 to workers over the internal network"
        Invoke-RemoteChecked -SshHost $sshMasterHost -InternalHost $masterHost -RemoteCommand "cd '${remoteRoot}' && chmod +x v3-distributed/distribute-partitions-from-master.sh && bash v3-distributed/distribute-partitions-from-master.sh" -Description "Distribute partitions from PC2"

        for ($i = $StartWorker; $i -le $EndWorker; $i++) {
            $hostName = $sshWorkerHosts[$i - 1]
            $internalHostName = $workerHosts[$i - 1]
            $partitionIndex = $i - 1
            Invoke-RemoteChecked -SshHost $hostName -InternalHost $internalHostName -RemoteCommand "test -f '${remoteRoot}/data/partitions-${partitions}/partition-${partitionIndex}.csv'" -Description "Verify partition-$partitionIndex exists on Worker $i"
        }
    } else {
        throw "Unsupported PARTITION_DISTRIBUTION_MODE=$partitionDistributionMode. Use 'master' or 'windows'."
    }
}

if ($SkipPermissions) {
    Write-Host "Skipping execution permissions because -SkipPermissions was provided."
} else {
    Write-Host "Applying execution permissions..."
    $permissionTargets = @()
    if (-not $SkipClient) {
        $permissionTargets += @{ SshHost = $sshClientHost; InternalHost = $clientHost }
    }
    if (-not $SkipVisualization) {
        $permissionTargets += @{ SshHost = $sshVisualizationHost; InternalHost = $visualizationHost }
    }
    if (-not $SkipMaster) {
        $permissionTargets += @{ SshHost = $sshMasterHost; InternalHost = $masterHost }
    }
    if (-not $SkipWorkers) {
        for ($i = $StartWorker; $i -le $EndWorker; $i++) {
            $permissionTargets += @{ SshHost = $sshWorkerHosts[$i - 1]; InternalHost = $workerHosts[$i - 1] }
        }
    }

    foreach ($target in $permissionTargets) {
        Invoke-RemoteChecked -SshHost $target.SshHost -InternalHost $target.InternalHost -RemoteCommand "cd '${remoteRoot}' && chmod +x v3-distributed/*.sh v3-distributed/*/build/install/*/bin/*" -Description "Apply execution permissions on $($target.SshHost)"
    }
}

Write-Host "Runtime deployment files copied successfully."
Write-Host "Start order:"
Write-Host "1. Visualization node: bash v3-distributed/start-node.sh visualization"
Write-Host "2. Workers: bash v3-distributed/start-node.sh worker <1..$partitions>"
Write-Host "3. Coordination node: bash v3-distributed/start-node.sh coordination"
Write-Host "4. Client node: bash v3-distributed/start-node.sh client"
