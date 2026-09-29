$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $python = Join-Path $PSScriptRoot '.venv/Scripts/python.exe'
    if (-not (Test-Path -LiteralPath $python)) {
        $launcher = Get-Command py -ErrorAction SilentlyContinue
        if ($launcher) { & $launcher.Source -3 -m venv .venv }
        else {
            $launcher = Get-Command python -ErrorAction Stop
            & $launcher.Source -m venv .venv
        }
        if ($LASTEXITCODE -ne 0) { throw 'Virtual environment creation failed; install Python 3.10 or newer.' }
    }
    $requirements = @('../../analytics/requirements.txt','../source/anticheat/analytics/requirements.txt') |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $requirements) { throw 'Open the source repository and install analytics/requirements.txt. The legacy trainer has been retired.' }
    & $python -m pip install -r $requirements
    if ($LASTEXITCODE -ne 0) { throw 'Dependency download failed. Recording and standard-library dataset tools are still usable.' }
    $locked = & $python -m pip freeze
    if ($LASTEXITCODE -ne 0) { throw 'Could not record dependency versions' }
    $locked | Set-Content -LiteralPath requirements-lock.txt -Encoding ASCII
    Write-Host 'Behavior framework dependencies ready. Use analytics/lab.py from the source repository; see analytics/README.md.'
} finally { Pop-Location }
