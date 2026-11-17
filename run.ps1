$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jar = Join-Path $root "build\libs\barry-0.1.0.jar"
$jattach = Join-Path $root "vendor\jattach\jattach.exe"
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

$action = $cmd[0]

switch ($action) {
    "list" {
        # list still uses Java to enumerate JVMs with display name filtering
        Write-Host "barry> list"
        & $java --add-modules jdk.attach -jar $jar list
    }
    "attach" {
        if ($cmd.Count -lt 2) {
            Write-Host "Usage: .\run.ps1 attach <pid>"
            exit 1
        }
        $targetPid = $cmd[1]
        $absJar = (Resolve-Path $jar).Path
        Write-Host "barry> attach PID $targetPid via jattach"
        & $jattach $targetPid load instrument false "$absJar=attach"
        if ($LASTEXITCODE -ne 0) { throw "jattach attach failed (exit $LASTEXITCODE)" }
        Write-Host "Agent loaded."
    }
    "eject" {
        if ($cmd.Count -lt 2) {
            Write-Host "Usage: .\run.ps1 eject <pid>"
            exit 1
        }
        $targetPid = $cmd[1]
        $absJar = (Resolve-Path $jar).Path
        Write-Host "barry> eject PID $targetPid via jattach"
        & $jattach $targetPid load instrument false "$absJar=eject"
        if ($LASTEXITCODE -ne 0) { throw "jattach eject failed (exit $LASTEXITCODE)" }
        Write-Host "Eject signal sent."
    }
    default {
        Write-Host "Usage: .\run.ps1 {list|attach <pid>|eject <pid>}"
    }
}
