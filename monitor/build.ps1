param(
    [string]$Jdk = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if(-not $Jdk){
    throw "Pass -Jdk with a JDK 21 root directory."
}

$javac = Join-Path $Jdk "bin\javac.exe"
$jar = Join-Path $Jdk "bin\jar.exe"
$java = Join-Path $Jdk "bin\java.exe"

foreach($binary in @($javac, $jar, $java)){
    if(-not(Test-Path -LiteralPath $binary -PathType Leaf)){
        throw "Missing JDK tool: $binary"
    }
}

function Invoke-Checked([string]$Program, [string[]]$Arguments){
    & $Program @Arguments

    if($LASTEXITCODE -ne 0){
        throw "$Program failed with exit code $LASTEXITCODE"
    }
}

Push-Location $PSScriptRoot
try{
    $classes = Join-Path $PSScriptRoot "build\classes"
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $sources = @(Get-ChildItem "src", "tests" -Filter "*.java" -Recurse |
        Sort-Object FullName | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
    $argfile = Join-Path $PSScriptRoot "build\sources.txt"
    [IO.File]::WriteAllLines($argfile, [string[]]$sources, [Text.UTF8Encoding]::new($false))

    Invoke-Checked $javac @(
        "--release", "8", "-encoding", "UTF-8",
        "-d", $classes, "@$argfile"
    )
    Invoke-Checked $java @(
        "-Djava.awt.headless=true", "-cp", $classes,
        "dev.fox.monitor.MonitorTest"
    )

    Invoke-Checked $java @(
        "-Djava.awt.headless=true", "-cp", $classes,
        "dev.fox.monitor.JavaRuntimeTest"
    )

    # Package application classes only; test fixtures stay outside the JAR.
    $appClasses = Join-Path $PSScriptRoot "build\app"
    New-Item -ItemType Directory -Path $appClasses -Force | Out-Null
    $sources = @(Get-ChildItem "src" -Filter "*.java" -Recurse |
        Sort-Object FullName | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
    [IO.File]::WriteAllLines($argfile, [string[]]$sources, [Text.UTF8Encoding]::new($false))
    Invoke-Checked $javac @("--release", "8", "-encoding", "UTF-8", "-d", $appClasses, "@$argfile")
    Invoke-Checked $jar @(
        "cfe", "anticheat-monitor.jar", "dev.fox.monitor.MonitorApp",
        "-C", $appClasses, "."
    )
    Write-Host "Built anticheat-monitor.jar"
}finally{
    Pop-Location
}
