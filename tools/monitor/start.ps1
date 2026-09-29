$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

try{
    $launcher = $null
    $roots = @()

    if($env:JAVA_HOME){
        $roots += $env:JAVA_HOME
    }

    foreach($parent in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java")){
        if(Test-Path -LiteralPath $parent){
            $roots += @(Get-ChildItem -LiteralPath $parent -Directory |
                Sort-Object Name -Descending | Select-Object -ExpandProperty FullName)
        }
    }

    foreach($root in $roots){
        $candidate = Join-Path $root "bin\javaw.exe"
        $release = Join-Path $root "release"

        # The desktop needs modern per-monitor DPI support. Spigot selects Java 8 separately.
        $modern = $false
        if(Test-Path -LiteralPath $release){
            $version = Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="(\d+)'
            if($version){ $modern = [int]$version.Matches[0].Groups[1].Value -ge 17 }
        }
        if($modern -and (Test-Path -LiteralPath $candidate)){
            $launcher = $candidate
            break
        }
    }

    if(-not $launcher){
        throw "The monitor needs Java 17 or newer for sharp Windows display scaling. Install Temurin 21 or set JAVA_HOME to it. Spigot still uses Java 8."
    }

    $jar = Join-Path $PSScriptRoot "anticheat-monitor.jar"
    $pendingJar = Join-Path $PSScriptRoot "anticheat-monitor.pending.jar"
    if(Test-Path -LiteralPath $pendingJar -PathType Leaf){
        try{
            Move-Item -LiteralPath $pendingJar -Destination $jar -Force
        }catch{
            throw "Close the existing monitor before applying its pending update. The update remains saved."
        }
    }

    if(-not(Test-Path -LiteralPath $jar -PathType Leaf)){
        throw "Missing anticheat-monitor.jar. Extract the complete monitor ZIP, or run build.ps1."
    }

    # javaw opens the desktop window without leaving a terminal attached.
    Start-Process -FilePath $launcher -ArgumentList @(
        '-Xmx256m',
        '-Dsun.java2d.uiScale.enabled=true',
        '-jar',
        ('"' + $jar + '"')
    ) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden
}catch{
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show(
        $_.Exception.Message,
        "Cannot start anticheat monitor"
    ) | Out-Null
    exit 1
}
