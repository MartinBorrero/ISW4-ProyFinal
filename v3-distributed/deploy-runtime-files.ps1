param(
    [string]$EnvFile = "v3-distributed/deploy.env"
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

function Read-DeployEnv {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        throw "Missing deployment env file: $Path"
    }

    $config = @{}
    $workers = @()
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
        $config["WORKER_HOSTS"] = $workers
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

function Copy-Scripts {
    param(
        [string]$HostName,
        [string]$InternalHost,
        [string]$User,
        [string]$RemoteRoot,
        [string[]]$Scripts
    )

    $items = @()
    $items += $Scripts
    $items += "v3-distributed/start-node.sh"
    $items += "v3-distributed/deploy.env"
    Copy-ToRemoteChecked -Sources $items -SshHost $HostName -InternalHost $InternalHost -RemotePath "${RemoteRoot}/v3-distributed/" -Description "Copy scripts to $HostName"
}

$config = Read-DeployEnv -Path $EnvFile

$sshUser = Require-Value -Config $config -Key "SSH_USER"
$remoteRoot = Require-Value -Config $config -Key "REMOTE_ROOT"
$visualizationHost = Require-Value -Config $config -Key "VISUALIZATION_HOST"
$masterHost = Require-Value -Config $config -Key "MASTER_HOST"
$sshVisualizationHost = if ($config.ContainsKey("SSH_VISUALIZATION_HOST")) { $config["SSH_VISUALIZATION_HOST"] } else { $visualizationHost }
$sshMasterHost = if ($config.ContainsKey("SSH_MASTER_HOST")) { $config["SSH_MASTER_HOST"] } else { $masterHost }
$partitions = [int](Require-Value -Config $config -Key "PARTITIONS")
$workerHosts = @($config["WORKER_HOSTS"])
$sshWorkerHosts = if ($config.ContainsKey("SSH_WORKER_HOSTS")) { @($config["SSH_WORKER_HOSTS"]) } else { $workerHosts }
$generateAndDistributePartitions = if ($config.ContainsKey("GENERATE_AND_DISTRIBUTE_PARTITIONS")) { $config["GENERATE_AND_DISTRIBUTE_PARTITIONS"].ToLowerInvariant() -ne "false" } else { $true }
$copyFullDatagramToMaster = if ($config.ContainsKey("COPY_FULL_DATAGRAM_TO_MASTER")) { $config["COPY_FULL_DATAGRAM_TO_MASTER"].ToLowerInvariant() -ne "false" } else { $true }

if ($workerHosts.Count -lt $partitions) {
    throw "WORKER_HOSTS has $($workerHosts.Count) entries, but PARTITIONS=$partitions"
}

if ($sshWorkerHosts.Count -lt $partitions) {
    throw "SSH_WORKER_HOSTS has $($sshWorkerHosts.Count) entries, but PARTITIONS=$partitions"
}

if ($generateAndDistributePartitions) {
    Require-Path "data/lines-241-ActiveGT.csv"
    if ($copyFullDatagramToMaster) {
        Require-Path "data/datagrams4Pilot.csv"
    }
}
Require-Path "v3-distributed/deploy.env"
Require-Path "gradlew.bat"

Write-Host "Building runtime distributions from Windows..."
Invoke-Checked { & .\gradlew.bat `
        "clean" `
        ":v3-distributed:visualization:installDist" `
        ":v3-distributed:client:installDist" `
        ":v3-distributed:master:installDist" `
        ":v3-distributed:broker:installDist" `
        ":v3-distributed:worker:installDist" `
        ":v3-distributed:partitioner:installDist" } "Gradle installDist"

Write-Host "Deploying PC1 Visualization + Client to $sshVisualizationHost"
Invoke-RemoteChecked -SshHost $sshVisualizationHost -InternalHost $visualizationHost -RemoteCommand "mkdir -p '${remoteRoot}/v3-distributed/visualization' '${remoteRoot}/v3-distributed/client' '${remoteRoot}/data'" -Description "Create PC1 directories"
Copy-ToRemoteChecked -Sources @("-r", "v3-distributed/visualization/build") -SshHost $sshVisualizationHost -InternalHost $visualizationHost -RemotePath "${remoteRoot}/v3-distributed/visualization/" -Description "Copy visualization to PC1"
Copy-ToRemoteChecked -Sources @("-r", "v3-distributed/client/build") -SshHost $sshVisualizationHost -InternalHost $visualizationHost -RemotePath "${remoteRoot}/v3-distributed/client/" -Description "Copy client to PC1"
Copy-Scripts -HostName $sshVisualizationHost -InternalHost $visualizationHost -User $sshUser -RemoteRoot $remoteRoot -Scripts @("v3-distributed/start-visualization.sh", "v3-distributed/start-client.sh")

Write-Host "Deploying PC2 Master + Broker to $sshMasterHost"
Invoke-RemoteChecked -SshHost $sshMasterHost -InternalHost $masterHost -RemoteCommand "mkdir -p '${remoteRoot}/v3-distributed/master' '${remoteRoot}/v3-distributed/broker' '${remoteRoot}/v3-distributed/partitioner' '${remoteRoot}/data'" -Description "Create PC2 directories"
Copy-ToRemoteChecked -Sources @("-r", "v3-distributed/master/build") -SshHost $sshMasterHost -InternalHost $masterHost -RemotePath "${remoteRoot}/v3-distributed/master/" -Description "Copy master to PC2"
Copy-ToRemoteChecked -Sources @("-r", "v3-distributed/broker/build") -SshHost $sshMasterHost -InternalHost $masterHost -RemotePath "${remoteRoot}/v3-distributed/broker/" -Description "Copy broker to PC2"
Copy-ToRemoteChecked -Sources @("-r", "v3-distributed/partitioner/build") -SshHost $sshMasterHost -InternalHost $masterHost -RemotePath "${remoteRoot}/v3-distributed/partitioner/" -Description "Copy partitioner to PC2"
Copy-Scripts -HostName $sshMasterHost -InternalHost $masterHost -User $sshUser -RemoteRoot $remoteRoot -Scripts @("v3-distributed/start-master.sh", "v3-distributed/start-broker.sh")
Copy-ToRemoteChecked -Sources @("v3-distributed/distribute-partitions-from-master.sh") -SshHost $sshMasterHost -InternalHost $masterHost -RemotePath "${remoteRoot}/v3-distributed/" -Description "Copy master partition distributor"

if ($generateAndDistributePartitions) {
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

for ($i = 1; $i -le $partitions; $i++) {
    $hostName = $sshWorkerHosts[$i - 1]
    $internalHostName = $workerHosts[$i - 1]
    Write-Host "Deploying Worker $i to $hostName"
    Invoke-RemoteChecked -SshHost $hostName -InternalHost $internalHostName -RemoteCommand "mkdir -p '${remoteRoot}/v3-distributed/worker' '${remoteRoot}/data'" -Description "Create Worker $i directories"
    Copy-ToRemoteChecked -Sources @("-r", "v3-distributed/worker/build") -SshHost $hostName -InternalHost $internalHostName -RemotePath "${remoteRoot}/v3-distributed/worker/" -Description "Copy worker build to Worker $i"
    Copy-Scripts -HostName $hostName -InternalHost $internalHostName -User $sshUser -RemoteRoot $remoteRoot -Scripts @("v3-distributed/start-worker.sh")
}

if ($generateAndDistributePartitions) {
    Write-Host "Distributing partitions from PC2 to workers over the internal network"
    Invoke-RemoteChecked -SshHost $sshMasterHost -InternalHost $masterHost -RemoteCommand "cd '${remoteRoot}' && chmod +x v3-distributed/distribute-partitions-from-master.sh && bash v3-distributed/distribute-partitions-from-master.sh" -Description "Distribute partitions from PC2"

    for ($i = 1; $i -le $partitions; $i++) {
        $hostName = $sshWorkerHosts[$i - 1]
        $internalHostName = $workerHosts[$i - 1]
        $partitionIndex = $i - 1
        Invoke-RemoteChecked -SshHost $hostName -InternalHost $internalHostName -RemoteCommand "test -f '${remoteRoot}/data/partitions-${partitions}/partition-${partitionIndex}.csv'" -Description "Verify partition-$partitionIndex exists on Worker $i"
    }
}

Write-Host "Applying execution permissions..."
$allHosts = @($sshVisualizationHost, $sshMasterHost) + $sshWorkerHosts[0..($partitions - 1)]
for ($i = 0; $i -lt $allHosts.Count; $i++) {
    $hostName = $allHosts[$i]
    if ($i -eq 0) {
        $internalHostName = $visualizationHost
    } elseif ($i -eq 1) {
        $internalHostName = $masterHost
    } else {
        $internalHostName = $workerHosts[$i - 2]
    }
    Invoke-RemoteChecked -SshHost $hostName -InternalHost $internalHostName -RemoteCommand "cd '${remoteRoot}' && chmod +x v3-distributed/*.sh v3-distributed/*/build/install/*/bin/*" -Description "Apply execution permissions on $hostName"
}

Write-Host "Runtime deployment files copied successfully."
Write-Host "Start order:"
Write-Host "1. PC1: bash v3-distributed/start-node.sh visualization"
Write-Host "2. Workers: bash v3-distributed/start-node.sh worker <1..$partitions>"
Write-Host "3. PC2: bash v3-distributed/start-node.sh coordination"
Write-Host "4. PC1: bash v3-distributed/start-node.sh client"
