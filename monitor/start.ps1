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

        if(Test-Path -LiteralPath $candidate){
            $launcher = $candidate
            break
        }
    }

    if(-not $launcher){
        $command = Get-Command "javaw.exe" -ErrorAction SilentlyContinue

        if($command){
            $launcher = $command.Source
        }
    }

    if(-not $launcher){
        throw "Java was not found. Install Temurin Java, or set JAVA_HOME to its root directory."
    }

    $jar = Join-Path $PSScriptRoot "anticheat-monitor.jar"

    if(-not(Test-Path -LiteralPath $jar -PathType Leaf)){
        throw "Missing anticheat-monitor.jar. Extract the complete monitor ZIP, or run build.ps1."
    }

    # javaw opens the desktop window without leaving a terminal attached.
    Start-Process -FilePath $launcher -ArgumentList @(
        '-Xmx256m',
        '-jar',
        ('"' + $jar + '"')
    ) -WorkingDirectory $PSScriptRoot
}catch{
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show(
        $_.Exception.Message,
        "Cannot start anticheat monitor"
    ) | Out-Null
    exit 1
}
