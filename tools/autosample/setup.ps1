param([switch]$SkipDependencies)
$ErrorActionPreference = 'Stop'
$cfg = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'config.json') -Raw | ConvertFrom-Json
$server = [IO.Path]::GetFullPath($cfg.server)
$jdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
$build = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Path $build -Force | Out-Null
$classes = Join-Path $build 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& "$jdk/bin/javac.exe" --release 8 -encoding UTF-8 -proc:none -cp "$server/spigot-1.8.8.jar" -d $classes "$PSScriptRoot/bridge/src/lab/AutoSampleLab.java"
if ($LASTEXITCODE -ne 0) { throw 'Observer compilation failed.' }
& "$jdk/bin/jar.exe" cf "$build/AutoSampleLab.jar" -C $classes . -C "$PSScriptRoot/bridge" plugin.yml
if ($LASTEXITCODE -ne 0) { throw 'Observer packaging failed.' }
$probe = [Net.Sockets.TcpClient]::new()
$running = $false
try { $probe.Connect('127.0.0.1', [int]$cfg.port); $running = $true } catch {} finally { $probe.Dispose() }
if ($running) { throw 'Server is running. Stop it normally before installing the observer.' }
$backup = Join-Path $PSScriptRoot ('backups/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$capture = Join-Path $server 'plugins/FoxAntiCheat/capture.properties'
Copy-Item -LiteralPath $capture -Destination (Join-Path $backup 'capture.properties')
$destination = Join-Path $server 'plugins/AutoSampleLab.jar'
if (Test-Path -LiteralPath $destination) { Copy-Item -LiteralPath $destination -Destination (Join-Path $backup 'AutoSampleLab.jar') }
Copy-Item -LiteralPath "$build/AutoSampleLab.jar" -Destination $destination -Force
# Patch only the recorder class family in the installed adapter. Preserve the
# rest of the deployed Java adapter and its native library byte-for-byte.
$adapter = Join-Path $server 'plugins/anticheat.jar'
$recorderSource = Join-Path (Split-Path $server -Parent) 'source/anticheat/plugin/src/main/java/dev/fox/anticheat/capture/CaptureRecorder.java'
if (-not (Test-Path -LiteralPath $recorderSource)) { throw 'Missing recorder source for Windows manifest publication fix.' }
$recorderClasses = Join-Path $build 'recorder-classes'
New-Item -ItemType Directory -Path $recorderClasses -Force | Out-Null
& "$jdk/bin/javac.exe" --release 8 -encoding UTF-8 -proc:none -d $recorderClasses $recorderSource
if ($LASTEXITCODE -ne 0) { throw 'Recorder compilation failed.' }
Copy-Item -LiteralPath $adapter -Destination (Join-Path $backup 'anticheat.jar')
$patchedAdapter = Join-Path $build 'anticheat.jar'
Copy-Item -LiteralPath $adapter -Destination $patchedAdapter -Force
& "$jdk/bin/jar.exe" uf $patchedAdapter -C $recorderClasses .
if ($LASTEXITCODE -ne 0) { throw 'Recorder patch packaging failed.' }
$observationSources = Join-Path (Split-Path $server -Parent) 'source/anticheat/plugin/src/main/java/dev/fox/anticheat/observation'
$observationClasses = Join-Path $build 'observation-classes'
New-Item -ItemType Directory -Path $observationClasses -Force | Out-Null
& "$jdk/bin/javac.exe" --release 8 -encoding UTF-8 -proc:none -cp "$adapter;$server/spigot-1.8.8.jar" -d $observationClasses "$observationSources/ChunkBoundaryScope.java" "$observationSources/MovementObservations.java"
if ($LASTEXITCODE -ne 0) { throw 'Movement observer compilation failed.' }
& "$jdk/bin/jar.exe" uf $patchedAdapter -C $observationClasses .
if ($LASTEXITCODE -ne 0) { throw 'Movement observer packaging failed.' }
Copy-Item -LiteralPath $patchedAdapter -Destination $adapter -Force
New-Item -ItemType Directory -Path "$($cfg.datasets)/raw","$($cfg.datasets)/automation" -Force | Out-Null
$captureText = [IO.File]::ReadAllText($capture)
$captureText = [regex]::Replace($captureText, '(?m)^directory=.*$', 'directory=' + $cfg.datasets.Replace('\','/') + '/raw')
[IO.File]::WriteAllText($capture, $captureText, [Text.UTF8Encoding]::new($false))
$installationFile = Join-Path $cfg.collector_tools 'installation.json'
if (Test-Path -LiteralPath $installationFile) {
    Copy-Item -LiteralPath $installationFile -Destination (Join-Path $backup 'installation.json')
    $installation = Get-Content -LiteralPath $installationFile -Raw | ConvertFrom-Json
    $installation.datasets = $cfg.datasets
    $installation.plugin_sha256 = (Get-FileHash -LiteralPath $adapter -Algorithm SHA256).Hash
    [IO.File]::WriteAllText($installationFile, ($installation | ConvertTo-Json) + "`n", [Text.UTF8Encoding]::new($false))
}
if (-not $SkipDependencies) {
    $python = Join-Path $PSScriptRoot '.venv/Scripts/python.exe'
    if (-not (Test-Path -LiteralPath $python)) {
        & py -3 -m venv "$PSScriptRoot/.venv"
        if ($LASTEXITCODE -ne 0) { throw 'Install Python 3.10 or newer.' }
    }
    & $python -m pip install --disable-pip-version-check -r "$PSScriptRoot/requirements.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Python dependency installation failed.' }
    & $python -m pip freeze | Set-Content -LiteralPath "$PSScriptRoot/requirements-lock.txt" -Encoding ASCII
}
Write-Host "Installed. New recordings: $($cfg.datasets)/raw"
Write-Host "Original configuration backed up to: $backup"
Write-Host 'Run legit_example.cmd after joining with the vanilla client.'
