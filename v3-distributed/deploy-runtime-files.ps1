param(
    [string]$EnvFile = "v3-distributed/deploy.env"
)

$ErrorActionPreference = "Stop"

function Invoke-Checked {
    param(
        [scriptblock]$Command,
        [string]$Description
    )

    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $Description"
    }
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
        [string]$User,
        [string]$RemoteRoot,
        [string[]]$Scripts
    )

    $destination = "${User}@${HostName}:${RemoteRoot}/v3-distributed/"
    $items = @()
    $items += $Scripts
    $items += "v3-distributed/start-node.sh"
    $items += "v3-distributed/deploy.env"
    Invoke-Checked { & scp @items $destination } "Copy scripts to $HostName"
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
        ":v3-distributed:visualization:installDist" `
        ":v3-distributed:client:installDist" `
        ":v3-distributed:master:installDist" `
        ":v3-distributed:broker:installDist" `
        ":v3-distributed:worker:installDist" `
        ":v3-distributed:partitioner:installDist" } "Gradle installDist"

Write-Host "Deploying PC1 Visualization + Client to $sshVisualizationHost"
Invoke-Checked { & ssh "${sshUser}@${sshVisualizationHost}" "mkdir -p '${remoteRoot}/v3-distributed/visualization' '${remoteRoot}/v3-distributed/client' '${remoteRoot}/data'" } "Create PC1 directories"
Invoke-Checked { & scp -r "v3-distributed/visualization/build" "${sshUser}@${sshVisualizationHost}:${remoteRoot}/v3-distributed/visualization/" } "Copy visualization to PC1"
Invoke-Checked { & scp -r "v3-distributed/client/build" "${sshUser}@${sshVisualizationHost}:${remoteRoot}/v3-distributed/client/" } "Copy client to PC1"
Copy-Scripts -HostName $sshVisualizationHost -User $sshUser -RemoteRoot $remoteRoot -Scripts @("v3-distributed/start-visualization.sh", "v3-distributed/start-client.sh")

Write-Host "Deploying PC2 Master + Broker to $sshMasterHost"
Invoke-Checked { & ssh "${sshUser}@${sshMasterHost}" "mkdir -p '${remoteRoot}/v3-distributed/master' '${remoteRoot}/v3-distributed/broker' '${remoteRoot}/v3-distributed/partitioner' '${remoteRoot}/data'" } "Create PC2 directories"
Invoke-Checked { & scp -r "v3-distributed/master/build" "${sshUser}@${sshMasterHost}:${remoteRoot}/v3-distributed/master/" } "Copy master to PC2"
Invoke-Checked { & scp -r "v3-distributed/broker/build" "${sshUser}@${sshMasterHost}:${remoteRoot}/v3-distributed/broker/" } "Copy broker to PC2"
Invoke-Checked { & scp -r "v3-distributed/partitioner/build" "${sshUser}@${sshMasterHost}:${remoteRoot}/v3-distributed/partitioner/" } "Copy partitioner to PC2"
Copy-Scripts -HostName $sshMasterHost -User $sshUser -RemoteRoot $remoteRoot -Scripts @("v3-distributed/start-master.sh", "v3-distributed/start-broker.sh")
Invoke-Checked { & scp "v3-distributed/distribute-partitions-from-master.sh" "${sshUser}@${sshMasterHost}:${remoteRoot}/v3-distributed/" } "Copy master partition distributor"

if ($generateAndDistributePartitions) {
    Write-Host "Copying full datagram only to PC2 and generating partitions there"
    if ($copyFullDatagramToMaster) {
        Invoke-Checked { & scp "data/datagrams4Pilot.csv" "${sshUser}@${sshMasterHost}:${remoteRoot}/data/" } "Copy full datagram to PC2"
    } else {
        Write-Host "Skipping full datagram copy because COPY_FULL_DATAGRAM_TO_MASTER=false"
        Invoke-Checked { & ssh "${sshUser}@${sshMasterHost}" "test -f '${remoteRoot}/data/datagrams4Pilot.csv'" } "Verify full datagram exists on PC2"
    }
    Invoke-Checked { & scp "data/lines-241-ActiveGT.csv" "${sshUser}@${sshMasterHost}:${remoteRoot}/data/" } "Copy routes file to PC2"
    Invoke-Checked { & ssh "${sshUser}@${sshMasterHost}" "cd '${remoteRoot}' && chmod +x v3-distributed/partitioner/build/install/partitioner/bin/partitioner && v3-distributed/partitioner/build/install/partitioner/bin/partitioner --datagrams data/datagrams4Pilot.csv --routes data/lines-241-ActiveGT.csv --output data/partitions-${partitions} --partitions ${partitions}" } "Generate partitions on PC2"
}

for ($i = 1; $i -le $partitions; $i++) {
    $hostName = $sshWorkerHosts[$i - 1]
    Write-Host "Deploying Worker $i to $hostName"
    Invoke-Checked { & ssh "${sshUser}@${hostName}" "mkdir -p '${remoteRoot}/v3-distributed/worker' '${remoteRoot}/data'" } "Create Worker $i directories"
    Invoke-Checked { & scp -r "v3-distributed/worker/build" "${sshUser}@${hostName}:${remoteRoot}/v3-distributed/worker/" } "Copy worker build to Worker $i"
    Copy-Scripts -HostName $hostName -User $sshUser -RemoteRoot $remoteRoot -Scripts @("v3-distributed/start-worker.sh")
}

if ($generateAndDistributePartitions) {
    Write-Host "Distributing partitions from PC2 to workers over the internal network"
    Invoke-Checked { & ssh -tt "${sshUser}@${sshMasterHost}" "cd '${remoteRoot}' && chmod +x v3-distributed/distribute-partitions-from-master.sh && bash v3-distributed/distribute-partitions-from-master.sh" } "Distribute partitions from PC2"
}

Write-Host "Applying execution permissions..."
$allHosts = @($sshVisualizationHost, $sshMasterHost) + $sshWorkerHosts[0..($partitions - 1)]
foreach ($hostName in $allHosts) {
    Invoke-Checked { & ssh "${sshUser}@${hostName}" "cd '${remoteRoot}' && chmod +x v3-distributed/*.sh v3-distributed/*/build/install/*/bin/*" } "Apply execution permissions on $hostName"
}

Write-Host "Runtime deployment files copied successfully."
Write-Host "Start order:"
Write-Host "1. PC1: bash v3-distributed/start-node.sh visualization"
Write-Host "2. Workers: bash v3-distributed/start-node.sh worker <1..$partitions>"
Write-Host "3. PC2: bash v3-distributed/start-node.sh coordination"
Write-Host "4. PC1: bash v3-distributed/start-node.sh client"
