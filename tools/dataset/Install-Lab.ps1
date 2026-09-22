param(
    [string]$LabRoot = 'C:/anticheat-lab',
    [string]$JavaHome = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot',
    [switch]$PrepareNewLab
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$lab = [IO.Path]::GetFullPath($LabRoot)
$server = Join-Path $lab 'server-1.8'
$collection = Join-Path $lab 'collection'
$dataset = Join-Path $lab 'datasets'
$properties = Join-Path $server 'server.properties'
if (-not (Test-Path -LiteralPath (Join-Path $server 'spigot-1.8.8.jar'))) { throw 'Expected existing Spigot 1.8.8 lab' }
foreach ($path in @($lab,$server,(Join-Path $server 'plugins'),(Join-Path $server 'plugins/FoxAntiCheat'))) {
    if ((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Refusing linked installation directory: $path" }
}
$latin = [Text.Encoding]::GetEncoding(28591)
$original = [IO.File]::ReadAllText($properties,$latin)
$portMatch = [regex]::Match($original,'(?m)^server-port=(\d+)')
$port = if ($portMatch.Success) { [int]$portMatch.Groups[1].Value } else { 25565 }
if ($PrepareNewLab) {
    if ((Test-Path -LiteralPath (Join-Path $server 'plugins/anticheat.jar')) -or (Test-Path -LiteralPath (Join-Path $server 'ac_collection_lab')) -or (Test-Path -LiteralPath (Join-Path $collection 'installation.json'))) { throw 'PrepareNewLab requires a fresh staging directory with no installed plugin or collection world.' }
} else {
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Any,$port)
    try { $probe.Start() } catch { throw 'Cannot verify that the server port is free. Stop the server and check socket permissions before installation.' } finally { $probe.Stop() }
}
$owned = Join-Path $collection 'installation.json'
if ((Test-Path -LiteralPath (Join-Path $server 'ac_collection_lab')) -and -not (Test-Path -LiteralPath $owned)) { throw 'Collection world name already exists without this setup marker; choose another lab directory.' }
foreach ($file in @('anticheat.jar','anticheat_native.dll')) {
    if (-not (Test-Path -LiteralPath (Join-Path $repo "build/libs/$file"))) { throw "Build first: missing $file" }
}
New-Item -ItemType Directory -Path $collection,(Join-Path $dataset 'raw'),(Join-Path $dataset 'derived'),(Join-Path $dataset 'reviews'),(Join-Path $dataset 'models') -Force | Out-Null
$backup = Join-Path $collection ('backups/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
foreach ($relative in @('server.properties','plugins/anticheat.jar','plugins/FoxAntiCheat/anticheat_native.dll','plugins/FoxAntiCheat/engine.conf','plugins/FoxAntiCheat/capture.properties')) {
    $source = Join-Path $server $relative
    if (Test-Path -LiteralPath $source) {
        $destination = Join-Path $backup $relative
        New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination
    }
}
$settings = [ordered]@{
    'level-name'='ac_collection_lab'; 'level-type'='FLAT';
    'generator-settings'='3;minecraft:bedrock,3*minecraft:stone,minecraft:grass;1;';
    'generate-structures'='false'; 'allow-nether'='false'; 'allow-flight'='true';
    'gamemode'='0'; 'force-gamemode'='true'; 'spawn-protection'='0'
}
$updated = $original
foreach ($key in $settings.Keys) {
    $pattern = '(?m)^' + [regex]::Escape($key) + '=.*$'
    if ([regex]::IsMatch($updated,$pattern)) { $updated = [regex]::Replace($updated,$pattern,$key+'='+$settings[$key]) }
    else { $updated += "`r`n$key=$($settings[$key])" }
}
[IO.File]::WriteAllText($properties,$updated,$latin)
Copy-Item -LiteralPath (Join-Path $repo 'build/libs/anticheat.jar') -Destination (Join-Path $server 'plugins/anticheat.jar') -Force
Copy-Item -LiteralPath (Join-Path $repo 'build/libs/anticheat_native.dll') -Destination (Join-Path $server 'plugins/FoxAntiCheat/anticheat_native.dll') -Force
$engine = [IO.File]::ReadAllText((Join-Path $repo 'plugin/src/main/resources/engine.conf')).Replace('trace=true','trace=false')
[IO.File]::WriteAllText((Join-Path $server 'plugins/FoxAntiCheat/engine.conf'),$engine,[Text.UTF8Encoding]::new($false))
$capture = "enabled=true`ndirectory=$($dataset.Replace('\','/'))/raw`nquota-gib=20`nserver-jar=spigot-1.8.8.jar`n"
[IO.File]::WriteAllText((Join-Path $server 'plugins/FoxAntiCheat/capture.properties'),$capture,[Text.UTF8Encoding]::new($false))
foreach ($file in @('dataset_tool.py','train_baseline.py','requirements.txt','Start-Collection.ps1','Start-Collection.cmd','Setup-Training.cmd','Setup-Training.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination (Join-Path $collection $file) -Force
}
$guide = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'COLLECTION_GUIDE.md')).Replace('C:/anticheat-lab',$lab.Replace('\','/'))
[IO.File]::WriteAllText((Join-Path $collection 'COLLECTION_GUIDE.md'),$guide,[Text.UTF8Encoding]::new($false))
$installation = [ordered]@{
    server=$server; datasets=$dataset; java_home=$JavaHome; port=$port; world='ac_collection_lab';
    backup=$backup; installed_utc=(Get-Date).ToUniversalTime().ToString('o');
    plugin_sha256=(Get-FileHash (Join-Path $server 'plugins/anticheat.jar')).Hash;
    native_sha256=(Get-FileHash (Join-Path $server 'plugins/FoxAntiCheat/anticheat_native.dll')).Hash
}
$installation | ConvertTo-Json | Set-Content -LiteralPath $owned -Encoding UTF8
Write-Output "Installed. Existing world folders preserved. Backup: $backup"
Write-Output "Start: $collection/Start-Collection.cmd"
