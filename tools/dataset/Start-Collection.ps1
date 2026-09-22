$ErrorActionPreference = 'Stop'
$settings = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'installation.json') -Raw | ConvertFrom-Json
$java = Join-Path $settings.java_home 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw 'Java 8 runtime from installation.json is missing' }
$probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Any,[int]$settings.port)
try { $probe.Start() } catch { throw 'The server port is already in use. Use the existing monitor/console.' } finally { $probe.Stop() }
Write-Host 'Collection server. Type commands here without a leading slash.'
Write-Host 'acdata status | acdata arena PLAYER | acdata start PLAYER legit none vanilla none walk | acdata stop PLAYER'
Write-Host 'Type stop to save the world and finish recordings. See COLLECTION_GUIDE.md.'
Push-Location $settings.server
try { & $java '-Xms1G' '-Xmx2G' '-jar' 'spigot-1.8.8.jar' 'nogui' }
finally { Pop-Location }
