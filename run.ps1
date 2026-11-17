$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jar = Join-Path $root "build\libs\barry-0.1.0.jar"
$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    $javaHome = "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
}
$java = Join-Path $javaHome "bin\java.exe"

if (-not (Test-Path $jar)) {
    Write-Host "Building..."
    & (Join-Path $root "gradlew.bat") jar
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
}

$cmd = $args
if ($cmd.Count -eq 0) { $cmd = @("list") }

Write-Host "barry> $($cmd -join ' ')"
& $java --add-modules jdk.attach -jar $jar @cmd
