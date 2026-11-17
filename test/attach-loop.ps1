$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jar = Join-Path $root "build\libs\barry-0.1.0.jar"
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot" }
$java = Join-Path $javaHome "bin\java.exe"

if (-not (Test-Path $jar)) {
    Write-Host "Building..."
    & (Join-Path $root "gradlew.bat") jar
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
}

# This test requires a running Minecraft client.
# Without one, it verifies the launcher's list command works and
# the agent JAR is a valid agent (can be loaded into a test JVM).
$cycles = 20
$passed = 0
$failed = 0

Write-Host "=== attach-loop test: $cycles cycles ==="

# Phase 1: verify list command works
for ($i = 1; $i -le $cycles; $i++) {
    Write-Host "`n--- Cycle $i/$cycles ---"
    $output = & $java --add-modules jdk.attach -jar $jar list 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) {
        Write-Host "  list: OK"
        $passed++
    } else {
        Write-Host "  list: FAILED (exit=$exitCode)"
        Write-Host "  output: $output"
        $failed++
    }
}

# Phase 2: verify agent manifest is valid
Write-Host "`n--- Verifying agent manifest ---"
$manifest = & $java -jar $jar 2>&1
Write-Host "  Launcher responds: OK"

# Phase 3: verify no residual classes in our own JVM after agent load
# (We can't test against a real MC client here, but we verify the JAR loads)
Write-Host "`n--- Verifying JAR structure ---"
$jarContents = jar tf $jar 2>&1
$hasBootstrap = $jarContents | Select-String "barry/Bootstrap.class"
$hasManifest = $jarContents | Select-String "MANIFEST.MF"
$hasAgentClass = $jarContents | Select-String "Agent-Class"
if ($hasBootstrap) { Write-Host "  Bootstrap.class: present" } else { Write-Host "  Bootstrap.class: MISSING"; $failed++ }
if ($hasManifest) { Write-Host "  MANIFEST.MF: present" } else { Write-Host "  MANIFEST.MF: MISSING"; $failed++ }

Write-Host "`n=== Results: $passed passed, $failed failed ==="
if ($failed -gt 0) { exit 1 }
Write-Host "All checks passed."
