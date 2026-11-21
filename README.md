# Minecraft Agents — Phase 1

This Fabric workspace launches the normal 3D Minecraft Java Edition client. The mod writes one startup message. Singleplayer uses Minecraft's built-in integrated server.

## Build and launch on Windows

Run from the repository root in PowerShell with JDK 17 available through `JAVA_HOME` or `PATH`.

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

The development client stores its settings and worlds in `run/client/`. Saves are under `run/client/saves/`, and the startup message `[Minecraft Agents] Phase 1 mod loaded.` appears in `run/client/logs/latest.log`. Git ignores the development run directory.

## Pinned versions

| Component | Version |
| --- | --- |
| Minecraft Java Edition | `1.20.1` |
| Java toolchain | `17` (tested with BellSoft Liberica `17.0.19+11-LTS`) |
| Fabric Loader | `0.16.14` |
| Fabric Loom | `1.8.13` |
| Gradle wrapper | `8.10.2` |
| Yarn mappings | `net.fabricmc:yarn:1.20.1+build.10:v2` |

Run `.\gradlew.bat genSources` to generate decompiled Minecraft sources with the pinned Yarn names. [Fabric's Java guidance](https://wiki.fabricmc.net/install) · [Loom 1.8 compatibility](https://fabricmc.net/2024/10/14/1212.html) · [Gradle Java compatibility](https://docs.gradle.org/8.10.2/userguide/compatibility.html) · [Yarn mappings](https://maven.fabricmc.net/net/fabricmc/yarn/1.20.1%2Bbuild.10/) · [Source generation](https://docs.fabricmc.net/develop/getting-started/intellij-idea/generating-sources)

## Verified on Windows 11 x64 (25H2)

1. `build` succeeded and produced `build/libs/minecraft-agents-0.1.0.jar`. The Minecraft title screen opened and the startup message appeared in the log.
2. Created and entered **Phase 1 Persistence**, a local Superflat world in Survival mode with cheats enabled for test setup and verification.
3. Right-clicked to place a gold block at **X 0, Y -60, Z 2** in the Overworld, selected **Save and Quit to Title**, then quit and relaunched the client.
4. Reopened the same world and confirmed the block with the F3 targeted-block display and `/execute if block 0 -60 2 minecraft:gold_block`, which returned `Test passed` before and after the restart.

The verified world is at `run/client/saves/Phase 1 Persistence/`. Open it from **Singleplayer** to inspect the saved block.
