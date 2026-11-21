# Executable Packaging Design

**Date**: 2026-11-19  
**Project**: donut-audit  
**Goal**: Create a distributable `.exe` that can run on other Windows PCs to inject the audit agent into Minecraft.

---

## Overview

Package the existing `audit-tool.exe` (already built in `src/injector/bin/Release/net7.0/win-x64/publish`) with:
1. A bundled JRE for true portability (works on any Windows PC without Java installed)
2. A simple console UI that shows "Injecting..." status and confirms success/failure

---

## Architecture

```
┌─────────────────────────────────────┐
│  audit-tool.exe (wrapper)           │
│  - Native .NET executable           │
│  - Bundles JRE 7.0 runtime          │
│  - Extracts embedded resources      │
│    (barry.jar, jattach.exe)         │
│  - Launches: java --add-modules     │
│    jdk.attach -cp barry.jar ...     │
└─────────────────────────────────────┘
              ↓
┌─────────────────────────────────────┐
│  Simple Console UI Layer            │
│  - "Finding Minecraft process..."   │
│  - "Injecting agent..."             │
│  - "✓ Injection successful!"        │
│  - "✗ Failed: <reason>"             │
└─────────────────────────────────────┘
              ↓
┌─────────────────────────────────────┐
│  Existing barry.launch.Main         │
│  (already handles attach/eject)     │
└─────────────────────────────────────┘
```

---

## Components

### 1. JRE Bundling
- **Tool**: `jlink` to create a minimal Java runtime
- **Target**: Java 17+ (matches .NET 7 runtime requirements)
- **Modules**: Only `jdk.attach`, `java.base`, `java.compiler`, etc. needed for attach API

### 2. Launcher UI
- **Type**: Console-based (simple text output to stdout/stderr)
- **Flow**:
  1. Print "Finding Minecraft process..."
  2. List found processes or show error
  3. On `attach` command: print "Injecting agent..."
  4. On success: print "✓ Injection successful!"
  5. On failure: print "✗ Failed: <error message>"

### 3. Distribution Package
- **Contents**:
  - `audit-tool.exe` (self-contained .NET app)
  - `jlink-jre/` (minimal JRE, ~20MB)
  - Optional: `README.txt` with usage instructions
- **Total size**: ~90MB

---

## Build Process

### Step 1: Create Minimal JRE
```bash
cd src/injector
dotnet new console -n jlink-jre --force
# Modify Program.cs to use jlink and output to bin/Release/net7.0/win-x64/jlink-jre/
dotnet publish -c Release -r win-x64 --self-contained true -o bin/Release/net7.0/win-x64/jlink-jre
```

### Step 2: Update injector.csproj
- Add `jlink-jre` as embedded resource (or copy to output folder)
- Modify `Program.cs` to extract JRE alongside the executable

### Step 3: Build Release Package
```bash
dotnet publish -c Release -r win-x64 --self-contained true \
  -o bin/Release/net7.0/win-x64/publish-with-jre
# Result: audit-tool.exe + jlink-jre folder
```

### Step 4: Create Simple Launcher UI (Optional Enhancement)
- Add a `LauncherUI.java` that wraps the existing attach logic with nicer output
- Or modify `Main.java` to print status messages before/after attaching

---

## User Experience

### Launch Flow
1. **User copies** `audit-tool.exe` + `jlink-jre` folder to target PC
2. **Opens Command Prompt**, navigates to folder
3. **Runs**: `.\audit-tool.exe attach 0` (or specific PID)
4. **Sees console output**:
   ```
   Finding Minecraft process...
   Minecraft JVMs:
     0) [PID 12345] net.minecraft.client.Minecraft
   Injecting agent...
   Agent loaded successfully.
   ✓ Injection successful!
   ```

### Success Indicators
- Console shows success message
- Minecraft GUI may show new menu (F6 key, if integrated with game overlay)
- ESP features visible in-game (if configured)

---

## Trade-offs

| Aspect | Pros | Cons |
|--------|------|------|
| **Bundled JRE** | Works on any Windows PC without Java install | ~20MB additional size |
| **Console UI** | Simple, no dependencies, easy to debug | Less "native" feel than GUI |
| **Self-contained .NET** | Single executable (plus JRE folder) | Requires .NET 7 runtime OR bundled JRE |

---

## Success Criteria

- [ ] `audit-tool.exe` runs on a clean Windows PC with no Java installed
- [ ] Console shows clear status messages during injection
- [ ] Agent successfully attaches to Minecraft process
- [ ] ESP features visible in-game after injection
- [ ] Package size < 100MB total

---

## Next Steps

1. ✅ Design approved - proceed to implementation plan
2. Create minimal JRE using `jlink`
3. Update build configuration for self-contained deployment
4. Add status message printing to UI flow
5. Test on clean Windows machine

---

**Questions?** Review the spec or ask before proceeding to implementation.
