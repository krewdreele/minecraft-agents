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

## ROCKET-3 trial

Run one villager at a time in graphical singleplayer. Aim at a block or mob to mark the target, then choose an interaction. The trial switches to the villager's view and sends the reference image, target mask, and live frames to a local worker.

1. Set up the [ROCKET-3 inference dependencies](https://github.com/CraftJarvis/ROCKET-3/tree/ddc91e4f3f62d51a9227d95c93a631294926a61c) in a separate Python environment. The CUDA smoke test used Ubuntu under WSL2, Python 3.11, PyTorch 2.7.1 with CUDA 12.8, and the additional resolver pins `numpy<2` and `pyglet==1.4.0b1` with the upstream requirements. Make the ROCKET-3 checkout importable through `PYTHONPATH` and download the [released checkpoint](https://huggingface.co/CraftJarvis/ROCKET-3-1.5x/tree/91a3bd12115befb210158d37ab302265deff4f5d).
2. From this repository, start the worker in that Python environment.

   ```sh
   python worker/rocket3_worker.py --checkpoint /path/to/rocket3.ckpt --device cuda --check
   python worker/rocket3_worker.py --checkpoint /path/to/rocket3.ckpt --device cuda
   ```

   Use `--mock` in place of the checkpoint for a no-op worker that checks the camera and transport. The worker listens at `127.0.0.1:8766`. Set `ROCKET3_PORT` in both launch environments to use a different port.
3. Launch Minecraft, create an arena with `/agent arena`, and aim at a block from close enough to reach it. For `approach`, you can also mark a mob within three blocks of you. Run one of the commands below and hold your view on the target as chat closes. Supply a mask width percentage, as in `/agent rocket Ada approach 12`, to resize the square around the target.
4. Check `/agent status Ada` for applied actions, late replies, blocked off-target attacks, and the outcome. Use `/agent stop Ada` to end the run. The camera and HUD return to the operator after the trial.

| Command | Result Fabric checks |
| --- | --- |
| `/agent rocket Ada approach` | Ada gets within three blocks of the marked block or mob. |
| `/agent rocket Ada break` | Ada breaks the marked block. |
| `/agent rocket Ada collect` | Ada breaks the marked block and picks up one of its drops. For tool-required blocks, start with a suitable tool in her selected slot. |
| `/agent rocket Ada use` | Ada toggles the marked lever or door that opens by hand, or presses the marked button. |
| `/agent rocket Ada` | The original oak-log collection trial. |

Use `/agent rocket Ada task walk to this block` for a plain-language alias. Other examples include `task mine this block`, `task collect this block's drops`, and `task toggle this block`. The crosshair chooses the target in every mode. Each run carries one visual goal and interaction. Compound goals need a planner to choose targets and sequence the interactions.

Inference runs outside the game tick, and Fabric discards replies older than three ticks. Restart the worker when switching to this version of the mod so it can receive the interaction ID.

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
