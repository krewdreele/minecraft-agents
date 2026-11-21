# Minecraft Agents

This Fabric workspace runs Minecraft Java Edition as a headless local world or in the normal 3D client. Singleplayer uses Minecraft's built-in integrated server. Agent tools act on named villagers with a real inventory and world position.

## Build and launch on Windows

Run from the repository root in PowerShell with JDK 17 available through `JAVA_HOME` or `PATH`.

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

The development client stores its settings and worlds in `run/client/`. Saves are under `run/client/saves/`, and the startup message `[Minecraft Agents] Phase 1 mod loaded.` appears in `run/client/logs/latest.log`. Git ignores the development run directory.

## Console mode

Run this in PowerShell from the repository root. It opens or creates **Console World** as a local Minecraft Survival save. Console mode uses Minecraft's world tick loop without a game window, renderer, or network listener.

```powershell
.\gradlew.bat -q runConsole
```

To use another save, pass its folder name under `run/client/saves/`.

```powershell
.\gradlew.bat -q runConsole '-PconsoleWorld=Phase 1 Persistence'
```

The terminal reports when the world opens and prints its world tick every 30 seconds. Type `status` for an immediate update, `save` to flush the world to disk, or `stop` to save and exit. Commands beginning with `/` run as Minecraft console commands. Detailed logs are in `run/console/logs/`.

To switch views, type `stop`, wait for the console run to exit, then launch `runClient` and select the same world in **Singleplayer**. Save and quit Singleplayer before reopening that world in console mode.

Verified on Windows 11. World ticks advanced with no game window, graphics or audio renderer modules, or listening network endpoint. A gold block placed at **X 0, Y 88, Z 0** survived a console restart, and the same save opened in graphical Singleplayer. Minecraft's save lock blocked a simultaneous attempt to open the same world.

## Agent tools in 3D

Launch `.\gradlew.bat runClient` and open **Agent Tools Demo** in Singleplayer. Ada and Ben are in front of the viewing platform. Use `/agent tools` for the in-game list. In a local world with cheats enabled, `/agent spawn Ada 0 -60 0` creates a named actor at the specified position. Actors wait for tool calls.

| Tool | Arguments | Action |
| --- | --- | --- |
| `inventory` | none | List carried items with slot numbers and counts. |
| `eat` | `slot` | Consume one food item and recover missing health. |
| `look` | optional `radius` (1–6, default 4), optional `block` | See nearby blocks and entities. Supply an exact block ID to find its visible positions. |
| `map` | optional `radius` (1–8, default 6) | View a north-up walkability grid, reachable cells, landmarks, and nearby mobs. |
| `go_to` | `x` and `z`, or `target` | Walk to an absolute map cell or near a named sign, player, or actor. |
| `move` | `direction` | Step up to one block north, south, east, or west using Minecraft collision. |
| `retreat` | none | Walk to a reachable destination farther from the closest known hostile. |
| `attack` | `entity_id`, optional `hits` and `weapon_slot` | Close to melee range and swing, optionally using a carried sword. |
| `place_block` | `block`, `x`, `y`, `z` | Place a carried block item against a solid face. |
| `craft` | `item`, optional `batches` (1–16, default 1) | Craft the specified item from carried ingredients with Minecraft's shaped and shapeless recipes. |
| `recipe_book` | optional `section` and `offset` | Read starter recipes, currently craftable recipes, or this actor's saved crafting history. |
| `place_sign` | `x`, `y`, `z`, `label` | Use a carried sign to mark a reachable spot with a label of up to 15 characters. |
| `break_block` | `x`, `y`, `z`, optional `tool_slot` | Break a reachable block and spawn its drops. Supply a suitable carried tool when required. |
| `gather` | `block`, optional `tool_slot` | Approach a breakable block by ID or tag, select a carried tool, and collect its drops. |
| `pickup` | `entity_id` | Pick up a nearby dropped item identified by `look`. |
| `open_chest` | `x`, `y`, `z` | Inspect an accessible chest, with an in-world sound and particle cue. |
| `take_from_chest` | `x`, `y`, `z`, `slot`, `count` | Take items from a chest slot. |
| `put_in_chest` | `x`, `y`, `z`, `slot`, `count` | Deposit items from an actor inventory slot. |
| `talk` | `target`, `message` | Deliver a short message to a nearby actor by UUID or unique name. |
| `read_messages` | none | Read the actor's latest 32 messages. |
| `recall` | optional `query`, `partner`, and `offset` | Search the actor's saved milestones and both sides of its conversations. |
| `write_knowledge` | `topic`, `note` | Record a discovery in the shared world journal. |
| `read_knowledge` | optional `query` | Read up to four recent notes matching a keyword in the topic or text. |

Try these in the saved demo. The chest is at `(1, -60, 1)` and Ada's placed plank is at `(0, -60, 2)`.

```text
/agent call Ada look {"radius":6}
/agent call Ada open_chest {"x":1,"y":-60,"z":1}
/agent call Ada move {"direction":"north"}
/agent call Ada move {"direction":"south"}
/agent call Ada talk {"target":"Ben","message":"The planks are beside the chest."}
/agent call Ben read_messages
```

`AgentTools.descriptors()` lists tool names, descriptions, and argument types. On Minecraft's server thread, call `AgentTools.call(world, actorUuid, toolName, jsonArguments)` to get `ok`, `message`, and structured `data`. The development commands use this same API. Larger command results go to `run/client/logs/latest.log`.

The original 11 tools were exercised in the 3D demo. Ada took and returned supplies, placed and broke a plank, mined stone with a carried pickaxe, picked up drops, and stopped at the chest when it blocked her path. Both actors received messages. The placed plank, Ada's inventory, and Ben's inbox were verified after a client restart.

### Food and shared knowledge

Open **Agent Learning Demo** in Singleplayer. Actors can place carried blocks with `place_block` and eat food by inventory slot. These villager actors use health as their food need. A food slot's `healing` value comes from the item's Minecraft nutrition, and actual healing is capped by missing health. The tool uses Minecraft's food effects and container returns. [Minecraft food and entity mappings](https://maven.fabricmc.net/docs/yarn-1.20.1%2Bbuild.10/net/minecraft/entity/LivingEntity.html#eatFood(net.minecraft.world.World,net.minecraft.item.ItemStack))

```text
/agent call Ada inventory
/agent call Ada eat {"slot":0}
/agent call Ada write_knowledge {"topic":"Food discovery","note":"One bread restored five health."}
/agent call Ben read_knowledge {"query":"Trial results"}
```

Each model starts with the long-term goal to survive, thrive, and learn about its world. It follows the current task and uses the journal for observed discoveries. Each save holds up to 128 shared notes, with author, location, dimension, and game tick. Writing the same topic again as the same actor updates that note. The model sees the latest two notes automatically, and an explicit search can pull older notes into its next turn.

The journal file for this save is `run/client/saves/Agent Learning Demo/data/minecraft_agents_knowledge.dat`.

Verified in the 3D client with GPT-6 Luna. Ada used one bread to recover from 11 to 16 health, placed one carried oak plank at `(2, -60, 0)`, and recorded both observations before finishing on turn four. A separate check with Ben kept stew in inventory at full health, then healed from 16 to 20 and returned a bowl. After a full client restart, the plank, Ada's health, and the notes persisted. Ben searched for an older note and correctly relayed the healing and block coordinates to Ada. A case-insensitive topic update appeared as a single note in the search results.

### Recipe book and working memory

```text
/agent call Ada recipe_book
/agent call Ada recipe_book {"section":"craftable"}
/agent call Ada recipe_book {"section":"crafted"}
/agent call Ada gather {"block":"minecraft:iron_ore"}
```

The recipe book shows six entries per page and supplies the next `offset` when there are more. The starter page covers planks, sticks, the crafting table, a wooden pickaxe, a wooden sword, and torches. The craftable page uses carried ingredients, available inventory space, and whether a crafting table is within reach. The crafted page records each actor's successful crafts from this version onward. Its history saves with the world. Ingredient tags and alternatives come from the loaded Minecraft recipes. [Yarn ingredient mappings](https://maven.fabricmc.net/docs/yarn-1.20.1%2Bbuild.10/net/minecraft/recipe/Ingredient.html)

Each decision includes the last five actions, their arguments, and their results. Routes and attacks record their final outcomes. The model also receives a short list of craftable items and its most recent successful recipes, along with notes from the one shared world notebook.

Each actor also has durable memory for up to 96 milestones and 96 conversation entries. Successful gathering, crafting, placement, healing, and other significant actions are recorded automatically, along with messages both sent and received. Exact repeats refresh the existing memory. The model sees a small selection of recent and goal-relevant entries each turn and can use `recall` to search older entries in pages of eight. The memory saves in `data/minecraft_agents_memory.dat` alongside the shared notebook. New events are recorded from this version onward.

```text
/agent call Ben recall {"query":"orchard","partner":"Ada"}
```

In **Agent Learning Demo**, Ada's placed plank and both sides of an orchard-marker conversation survived saving and reopening the world. Five newer messages had arrived before the recall check. In a fresh GPT-6 Luna loop, Ben recovered the exact marker, **Silver Acacia**, and told Ada. He used a second recall after his first talk attempt was rejected.

`gather` automatically selects tools from inventory and respects Minecraft's mining tiers. Carried Silk Touch tools and shears can change what drops. Supply `tool_slot` to choose a particular tool. Gather searches exposed breakable blocks in the loaded local area and collects Minecraft's drops.

Verified in **Agent Systems Demo**, a separate local console save. Ada walked through grass, saw a two-block rise as `#`, and could not route into a cell marked `L`. She gathered raw iron with a carried stone pickaxe and collected grass with carried shears. The recipe book added 3×3 recipes when she placed a table, and its next page included a wooden sword. After reopening the save, Ada's four crafted recipes persisted and Ben could still read her journal note. A deterministic history check retained exactly the last five actions and outcomes after seven writes. In a live GPT-6 Luna check, Ada still repeated inventory checks and reached the turn limit before finishing the requested sequence.

### Map and destinations

Open **Agent Navigation Demo** in Singleplayer. The map starts at `(minX, minZ)`. X increases across columns and Z increases down rows. `@` marks the actor, `.` marks level walking space, `^` and `v` mark one-block elevation changes, and `#` and `?` mark blocked and unavailable cells. `L` marks lava at the mapped height. Grass can be walked through. Steep terrain, obstacles, and missing support can appear as `#`. The model also receives a short list of reachable `destinations` with absolute coordinates.

```text
/function navbench:reset
/agent call Ada map {"radius":6}
/agent call Ada go_to {"x":0,"z":4}
/agent call Ada look
```

Wait for the reset to respawn the actors before issuing a tool call. Routes use breadth-first search over cardinal neighbors in the loaded area within eight blocks in each horizontal direction. The grid checks full-block footing and headroom in the layer at the actor's feet and the layers immediately above and below. Actors follow the route with Minecraft collision. A blocked waypoint ends the route and leaves a result in `look`.

### Shared landmarks and block search

Open **Agent Landmark Demo** in Singleplayer. Use `/agent-map` to center a map on yourself, or `/agent call Ben map` to see Ben's map. Both maps label nearby signs, players, actors, and chests as `S`, `P`, `A`, and `C`. The model's map keeps the terrain grid separate from up to 12 landmark entries with labels and coordinates.

```text
/agent-map 8
/agent call Ben go_to {"target":"sign:Farm"}
/agent call Ben go_to {"target":"player:YourMinecraftName"}
/agent call Ben look {"block":"minecraft:stone_bricks","radius":6}
```

An actor with a carried sign can use `/agent call Ada place_sign {"x":0,"y":-60,"z":5,"label":"Storage"}` at a reachable spot with support. The tool consumes a sign and writes the label on both sides. The map reads the first nonempty line of nearby signs from the saved world. Player-placed signs and edits appear too. Use distinct labels for named routes.

Named targets can be up to 16 blocks away along each horizontal axis and six blocks vertically in the loaded area of the same dimension. The actor walks to a clear spot near the target's position when the command starts. Model prompts show up to six distinct visible block types in addition to the map. An exact `look` query filters before the result limit and returns up to six matching positions in the next model observation.

Verified in the 3D client. Ada placed **Farm** at `(1, -60, 4)` using one carried oak sign. It appeared on both maps and retained its text on both sides after saving and restarting the client. Ben took six steps to reach the sign, and Ada took nine steps toward the named player. GPT-6 Luna found eight visible stone-brick positions, repeated the search once, walked through the doorway toward Farm, and called `finish` on turn four.

### Melee in the combat demo

Open **Agent Combat Demo** in Singleplayer. Find the husk or sheep with `/agent call Scout look {"radius":6}`, then replace `184` in the example with the current ID shown beside that target.

```text
/agent call Scout attack {"entity_id":184,"hits":3}
/agent call Scout look {"radius":6}
```

`attack` starts an action that runs on Minecraft ticks. It accepts a target within six blocks and defaults to one swing attempt, with a maximum of five. The actor walks straight toward the target using Minecraft collision and swings only in melee range with line of sight. Blocked paths, unavailable targets, and the 100-tick time limit end the action. Player targets must be vulnerable and PvP must be enabled. Use `look` for the latest status, including attempted swings and successful hits.

## Run an actor with a model

Launch `.\gradlew.bat runClient` and open **Agent Loop Demo** in Singleplayer. Ada and Ben are ready in front of the viewing platform. The development launchers read `OPENAI_API_KEY` and optional `OPENAI_MODEL` from the repository's ignored `.env` file. Environment variables take precedence. The model defaults to GPT-6 Luna, which runs these decisions with reasoning effort set to `none`. [GPT-6 Luna model details](https://developers.openai.com/api/docs/models/gpt-6-luna)

```text
/agent run Ada 4 Tell Ben that the plank is ready, then finish.
/agent status Ada
/agent stop Ada
```

The turn limit can be 1–20. Each request includes the goal, a compact nearby snapshot with the 2D map, current inventory, up to two recent messages, and up to five recent tool results. The model receives the tools above and picks one per turn. It can call `finish` to end early. The loop waits for routes, gathering, retreats, and attacks to finish before asking for the next action. Network requests run asynchronously and world actions execute on Minecraft's server thread. Nearby players see actions in chat. The game log records selected tools, tokens, and request-to-application latency.

Use `/agent run-local Ada 7 <goal>` to send the same tools and observations to Ollama at `127.0.0.1:11434`. It defaults to `qwen3.5:4b` with thinking off and a 4,096-token context. Set `OLLAMA_MODEL` in the environment or workspace `.env` to choose another downloaded model. [Ollama tool calling](https://docs.ollama.com/capabilities/tool-calling)

Verified in the 3D client with GPT-6 Luna. In a solo run, Ada inspected a chest, took one plank, placed it at `(0, -60, 2)`, and finished in four turns. In overlapping runs, Ben asked if the plank was ready, Ada answered, and Ben read the reply and thanked her. Those runs used 961–1,166 input tokens per decision, including tool definitions. The operator stop command also ended a run during its first request.

### Two-agent survival trial

Open **Agent Survival Demo** in Singleplayer. The arena has three oak trees, sheep and a cow, and two villager actors. The observer used spectator mode. The trial disabled random mob spawning and scheduled nightfall and two active zombies 22 seconds after the agents started. The local datapack's `survivaltrial:run` function records the two launch commands and the nightfall schedule. Ada received this goal.

> Survive the first night with Ben. Gather wood and craft useful gear. Hostile mobs come at night. Use the map to avoid or fight nearby hostiles.

Maps show `!` for hostiles and `M` for animals. The map's `mobs` entries contain the mob type, position, and entity ID for `attack`. The loop also reports the actor's health, time of day, and whether it is night.

```text
/agent call Ada map {"radius":8}
/agent call Ada craft {"item":"minecraft:oak_planks","batches":2}
/agent call Ada inventory
```

Crafting uses the ingredients already carried by the actor. A batch is one recipe execution, so two batches of oak planks consume two logs and produce eight planks. A 3×3 recipe needs a visible crafting table within reach. Inventory changes commit when every batch succeeds and all outputs and container remainders fit. The tool supports shaped and shapeless crafting recipes with a defined output item. A controlled sword test dealt four damage and consumed one durability. [Minecraft recipe manager mappings](https://maven.fabricmc.net/docs/yarn-1.20.1%2Bbuild.10/net/minecraft/recipe/RecipeManager.html)

| Run | Observed result |
| --- | --- |
| Ada and Ben, empty inventories, up to 16 turns each | Sixteen of 22 pre-nightfall decisions were scans or inventory checks. Ada collected one log. Ben twice tried to pick up a drop from outside pickup range. Neither crafted. Both attacked zombies and died by about 11 seconds after nightfall. |
| Ada, two carried oak logs, seven turns | Crafted eight planks, four sticks, and a crafting table, placed the table, then crafted a wooden sword. Two inventory checks used the remaining turns, and the run ended at its limit. |

The first run exposed two steps that needed their own tools. Gathering combined walking, breaking, and pickup. Retreating required choosing a reachable destination that increased distance from hostiles.

### Survival retest with longer preparation

**Agent Survival Retest** is a separate single-player save. The retest used the same two actors and model in a similar oak-and-animal arena. Both actors started with empty inventories and received a 45-second warning to gather two logs and craft a wooden sword. The local `survivalretest:run` function starts both actors. At nightfall a second prompt asks them to stay alive and retreat from close hostiles, and two active zombies spawn near them. The trial checks survival 25 seconds after nightfall.

```text
/agent call Ada gather {"block":"#minecraft:logs"}
/agent call Ada retreat
```

`gather` searches the loaded local area, walks into sight and reach of one matching block, and uses Minecraft's block loot. Any overflow drops at the block. `retreat` plans around nearby hostile positions using the local walkability grid. Each retreat is a single route, so agents can reassess if the mobs move. [Minecraft block loot mappings](https://maven.fabricmc.net/docs/yarn-1.20.1%2Bbuild.10/net/minecraft/block/Block.html#getDroppedStacks(net.minecraft.block.BlockState,net.minecraft.server.world.ServerWorld,net.minecraft.util.math.BlockPos,net.minecraft.block.entity.BlockEntity,net.minecraft.entity.Entity,net.minecraft.item.ItemStack))

The direct checks verified walking toward and collecting an oak log six blocks away, and a 16-step retreat from a fixed zombie. In the two-agent run, each actor gathered three logs. At nightfall Ada carried two logs and four planks, while Ben carried two logs and a crafting table. Of the 38 preparation decisions, 26 were inventory checks, and neither actor finished a sword. Both completed four retreat routes against the active zombies and were alive at the 25-second check. Ada had 17/20 health and Ben had 20/20. The test stopped the runs and removed the two spawned zombies at that point.

### Navigation benchmark

Each provider ran the same two tasks with freshly spawned actors in the 3D navigation arena. The solo task required the eight-step doorway route, one plank taken from the chest, and placement at `(0, -60, 5)`. The pair task required a question, a ready reply, and a thank-you.

| Model | Doorway and plank | Two-actor exchange | Median turn latency |
| --- | --- | --- | --- |
| GPT-6 Luna | Placed the plank and called `finish` in 5 turns, about 11 s | All three messages delivered | 975 ms across 12 turns |
| Qwen3.5 4B Q4_K_M | Reached the destination and took one plank. Placement was rejected on the last turn. | Question and reply delivered. The thank-you was skipped. | 598 ms across 15 warm turns |

These are the final matched trials with the reachable destination list. Both models traversed the same eight-step doorway route and reduced the chest count from four planks to three. Qwen completed placement in one earlier trial. In a separate free-choice task, Luna chose a cell beyond the wall and finished. Qwen chose a reachable cell on the wrong side and hit its turn limit. The warmed local runs used Ollama 0.40.17 on the RTX 5060 Ti. The separate first-load probe exceeded 60 seconds. Turn latency measures the time from sending a request to applying the response on a Minecraft tick.

The local demo save includes the `navbench:reset`, `navbench:ollama_build`, `navbench:openai_build`, `navbench:ollama_pair`, and `navbench:openai_pair` functions. Reset before each trial and wait for both actors to respawn. Run `navbench:verify_build` after a build trial to check arrival and placement. The portable benchmark runtime and downloaded model are under `%TEMP%\minecraft-agents-ollama-0.40.17`.

### First society trial

**Agent Society Trial 01** is a generated single-player Survival world on Normal difficulty. The trial uses the natural day cycle and mob spawning. Ada, Ben, and Scout start with empty inventories. The local observer uses Creative flight so the world can spawn mobs naturally around the group.

```text
/agent trial Ada Ben Scout
/agent trial status
```

The trial ends at 120,000 world ticks, equivalent to five Minecraft days, or when all three actors die. Daylight decisions have an eight-second delay, and nighttime decisions have a four-second delay, plus model response and action time. The controller keeps the selected actors' chunks ticking and logs health and positions once per minute. `/agent trial stop` saves and ends a trial early when requested.

At the endpoint, the controller stops the loops, saves the world, and closes the single-player session. Its summary is in `run/client/saves/Agent Society Trial 01/agent-trial-result.json`. Open **Agent Society Trial 01** from Singleplayer to continue from the saved world.

The first run was interrupted before nightfall and saved at a day time of 10,698 ticks. The tool checks used a separate save.

### Three-day society trial

**Agent Society Trial 02** uses the same generated seed as Trial 01 and starts Ada, Ben, and Scout at dawn with empty inventories. It runs in the 3D Minecraft client. The flying Creative observer has night vision for the view, and the world keeps its normal day cycle and mob spawning.

The final argument selects the duration in Minecraft days. The command defaults to five days when omitted.

```text
/agent trial Ada Ben Scout 3
/agent trial status
```

The three-day limit is 72,000 world ticks. The controller also ends and saves if all three actors die. Reopen **Agent Society Trial 02** in Singleplayer to continue the saved world. The endpoint report is `run/client/saves/Agent Society Trial 02/agent-trial-result.json`.

Use `/agent trial pair Ada Ben 3` for a two-agent trial with the same duration and early stop when both actors die.

**Agent Society Trial 03** is the two-agent run with durable memory. Ada and Ben start from empty inventories at dawn on the same seed. The regular 3D client shows the run, and the endpoint report is `run/client/saves/Agent Society Trial 03/agent-trial-result.json`.

Trial 03 ended in the first night at 16,287 world ticks, about 13 minutes and 34 seconds into the run. Both agents died to naturally spawned zombies. Ben placed a crafting table, made 24 slabs, and completed one retreat. The pair delivered 25 messages, and 133 of their 215 decisions were looks, maps, inbox reads, or inventory checks. The client saved the world and the durable memories before closing the session.

## Agent-authored skills

Launch the visible client with `.\gradlew.bat runClient`. In a Survival world with cheats enabled, create two nearby actors and run `/agent trial pair Ada Ben 1`. The pair has one Minecraft day to survive and can write, share, and reuse skills as it goes.

| Tool | Use |
| --- | --- |
| `save_skill` | Publish a named program of existing tools. Each revision by its author gets a new version. |
| `find_skills` | Browse by keyword or read a named program and its parameters. |
| `run_skill` / `skill_status` | Run any saved version with supplied arguments and read the final result. |

Skill arguments like `"$direction"` become named parameters. Each step can repeat up to four times. An `if` check can skip a step based on the previous result. An `assert` checks the current result and stops the skill if it fails. Runs use up to eight steps, 12 calls, and 600 server ticks. They wait for movement, attacks, or gathering to finish and stop on action failure, damage, or a close visible hostile. Effects from completed steps persist.

```text
/agent call Ada save_skill {"name":"walk_twice","description":"Take two steps","steps":[{"tool":"move","args":{"direction":"$dir"},"repeat":2}]}
/agent call Ben run_skill {"name":"walk_twice","args":{"dir":"east"}}
/agent call Ben skill_status
```

The shared skill library saves with the world in `run/client/saves/<world>/data/minecraft_agents_skills.dat`. Skills from an older save that use the retired `inspect_block` tool need a revision before they can run.

## Pinned versions

| Component | Version |
| --- | --- |
| Minecraft Java Edition | `1.20.1` |
| Java toolchain | `17` (tested with BellSoft Liberica `17.0.19+11-LTS`) |
| Fabric Loader | `0.16.14` |
| Fabric Loom | `1.8.13` |
| Fabric API command and lifecycle modules | `0.92.2+1.20.1` API release |
| Gradle wrapper | `8.10.2` |
| Yarn mappings | `net.fabricmc:yarn:1.20.1+build.10:v2` |

Run `.\gradlew.bat genSources` to generate decompiled Minecraft sources with the pinned Yarn names. [Fabric's Java guidance](https://wiki.fabricmc.net/install) · [Loom 1.8 compatibility](https://fabricmc.net/2024/10/14/1212.html) · [Gradle Java compatibility](https://docs.gradle.org/8.10.2/userguide/compatibility.html) · [Yarn mappings](https://maven.fabricmc.net/net/fabricmc/yarn/1.20.1%2Bbuild.10/) · [Source generation](https://docs.fabricmc.net/develop/getting-started/intellij-idea/generating-sources)

## Phase 1 verified on Windows 11 x64 (25H2)

1. `build` succeeded and produced `build/libs/minecraft-agents-0.1.0.jar`. The Minecraft title screen opened and the startup message appeared in the log.
2. Created and entered **Phase 1 Persistence**, a local Superflat world in Survival mode with cheats enabled for test setup and verification.
3. Right-clicked to place a gold block at **X 0, Y -60, Z 2** in the Overworld, selected **Save and Quit to Title**, then quit and relaunched the client.
4. Reopened the same world and confirmed the block with the F3 targeted-block display and `/execute if block 0 -60 2 minecraft:gold_block`, which returned `Test passed` before and after the restart.

The verified world is at `run/client/saves/Phase 1 Persistence/`. Open it from **Singleplayer** to inspect the saved block.
