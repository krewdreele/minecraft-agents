# Minecraft Agents

Minecraft villagers learn reusable keyboard and mouse programs from demonstrations and their own experiments. The mod runs in the normal 3D client or a headless local world.

## Launch on Windows

Use JDK 17 and run from the repository root in PowerShell.

```powershell
.\gradlew.bat runClient
```

Open a Singleplayer world with cheats enabled. For a headless world, run `.\gradlew.bat runConsole`. You can select a save with `'-PconsoleWorld=My World'`. The headless terminal accepts Minecraft commands starting with `/` and the commands `status`, `save`, and `stop`.

Worlds are stored under `run/client/saves/`. The 3D client log is `run/client/logs/latest.log`, and headless logs are under `run/console/logs/`. Save and close a world before switching between clients. In a OneDrive checkout, Gradle puts build outputs in a checkout-specific directory under `%LOCALAPPDATA%\MinecraftAgents\builds`.

## Start an arena

```text
/agent arena
```

This builds a 21-by-21 starter arena above the local terrain and brings the player to its entrance. Two oak trees give the agents something to explore and mine. The first arena has Ada and Ben, with empty inventories and a new shared library for skills and demonstrations. Later arenas have pairs such as Ada2 and Ben2 and their own libraries. Record player demonstrations from inside the arena where the agents will learn them.

## Record, learn, and use a skill

```text
/agent demo record mine_a_log
/agent demo stop
/agent run Ada 12 Study mine_a_log, infer its purpose, and save and test a reusable skill called get_wood.
/agent do Ben get_wood
/agent adapt Ben 12 get_wood
```

Between the two demo commands, perform the complete task. For wood, find and approach an intact log, mine it, then collect the drop. Add an actor name to the demo commands to record an agent. For example, use `/agent demo record mine_a_log Ada` and `/agent demo stop Ada`.

Agents see the recorded inputs and observed changes together. They report what the actions were meant to achieve and how to verify it. A saved revision should handle the original starting scene and resume from partial progress. Failed tests provide the expected result, actual movement, before and after views, and observed effects. The agent explains its next adjustment, then the game retests the saved revision. Registered inventory goals require the specified gain during the test.

`/agent do Ben get_wood` runs the latest version directly. Add a version number to pin it, as in `/agent do Ben get_wood 2`. Agents can run skills shared in their library. An agent can revise its own skill or save a fork of another agent's skill.

During adaptation, a nested failure points to the exact called skill version. The agent revises that skill, and the game updates the pinned calls along the path back to the root, forking shared parents when needed. It then retests the full skill. Give reusable sub-skills assertions for their own expected results. An inventory `increase` check measures the gain since the current skill call began.

### Model settings

`run` and `adapt` use `OPENAI_API_KEY` and optional `OPENAI_MODEL` from the environment or workspace `.env`. Use `run-local` and `adapt-local` for Ollama at `127.0.0.1:11434`. Set `OLLAMA_MODEL` to choose a downloaded model. Each model run takes a budget of 1 to 20 turns.

### Inspect and debug

| Command | Result |
| --- | --- |
| `/agent skills Ben` | List skills in Ben's library. |
| `/agent skills Ben get_wood 2` | Inspect a saved program. Omit the version for the latest. |
| `/agent view Ben` | Show yaw and pitch, and log the full camera view. |
| `/agent status Ben` | Check the model run and last skill execution. |
| `/agent stop Ben` | Stop the actor's current work. |
| `/agent debug Ben plan` | Inspect the last submitted program, including a rejected draft. |
| `/agent debug Ben run` | Inspect the program and exact version most recently executed. |
| `/agent debug Ben trace on` | Record movements, condition results, and effects during execution. |
| `/agent debug Ben trace` | Inspect the last trace. Use `trace off` to turn off recording. |

Click **Copy pseudocode** in chat to copy a program. The full pseudocode and traces go to the run log.

### Program inputs and observations

Programs dispatch virtual keyboard and mouse inputs and use `if`, `while`, `assert`, and version-pinned `call` instructions. Actors see blocks, entities, their inventory, and their pose. Yaw is the horizontal heading. Pitch is measured in degrees, with zero level, negative looking up, and positive looking down. The pose also reports whether the actor is on the ground.

`{"mouse":{"pitch_to":0}}` levels the view from any initial pitch. A wood skill can run this before its horizontal search, then use the visible log's bearing and elevation to aim. A skill can share its pickup path between a fresh mining attempt and a scene with an existing drop.

Runs are bounded to 128 inputs, 384 instructions, and 2,400 ticks. Libraries persist with the world.

## Build versions

| Component | Version |
| --- | --- |
| Minecraft Java Edition | `1.20.1` |
| Java toolchain | `17` |
| Fabric Loader | `0.16.14` |
| Fabric Loom | `1.8.13` |
| Fabric API command and lifecycle modules | `0.92.2+1.20.1` |
| Gradle wrapper | `8.10.2` |
| Yarn mappings | `net.fabricmc:yarn:1.20.1+build.10:v2` |
