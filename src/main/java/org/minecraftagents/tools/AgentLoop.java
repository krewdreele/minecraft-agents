package org.minecraftagents.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** One bounded model decision at a time per actor, applied on the server thread. */
public final class AgentLoop {
    static final int MAX_TURNS = 20;
    private static final int HISTORY_LIMIT = 5;
    private static final int TURN_DELAY = 20;
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentLoop.class);
    private static final Map<ServerWorld, Map<UUID, Run>> WORLDS = new HashMap<>();

    private AgentLoop() {
    }

    static String start(ServerWorld world, VillagerEntity actor, int turns, String goal, AgentModelClient.Provider provider,
                        UUID operator) {
        return start(world, actor, turns, goal, AgentModelClient.load(provider), operator, null);
    }

    static String adapt(ServerWorld world, VillagerEntity actor, int turns, String name, AgentModelClient.Provider provider,
                        UUID operator) {
        var args = new JsonObject();
        args.addProperty("name", name);
        var skill = AgentSkills.adaptationTarget(world, actor, args);
        return start(world, actor, turns, "Test and adapt " + name + " to achieve its original purpose.",
                AgentModelClient.load(provider), operator, skill);
    }

    static boolean isRunning(ServerWorld world, UUID actorId) {
        var runs = WORLDS.get(world);
        Run run = runs == null ? null : runs.get(actorId);
        return run != null && run.running();
    }

    private static String start(ServerWorld world, VillagerEntity actor, int turns, String goal, AgentModelClient client,
                                UUID operator, JsonObject skill) {
        UUID actorId = actor.getUuid();
        if (goal.isBlank() || goal.length() > 200) {
            throw new IllegalStateException("Use a goal of 1 to 200 characters.");
        }
        var runs = WORLDS.computeIfAbsent(world, key -> new HashMap<>());
        Run previous = runs.get(actorId);
        if (previous != null && previous.running()) {
            throw new IllegalStateException("This actor already has an active run.");
        }
        var run = new Run(client, goal, turns, operator, actor, skill);
        if (skill != null) {
            var previousAttempt = AgentSkills.completed(world, actor);
            if (previousAttempt != null && AgentSkills.Ref.from(skill).equals(AgentSkills.Ref.from(previousAttempt.details()))) {
                run.assessSkill(previousAttempt);
                if (Boolean.TRUE.equals(run.lastSkillPassed)) {
                    run.lastSkillPassed = null;
                }
            }
        }
        runs.put(actorId, run);
        return "Started " + actor.getName().getString() + " with " + run.model
                + " for up to " + turns + " turns.";
    }

    static String status(ServerWorld world, UUID actorId) {
        var run = WORLDS.getOrDefault(world, Map.of()).get(actorId);
        return run == null ? null : run.model + " · " + run.turnLabel() + " turns. " + run.detail;
    }

    static boolean stop(ServerWorld world, UUID actorId) {
        var run = WORLDS.getOrDefault(world, Map.of()).get(actorId);
        boolean running = run != null && run.running();
        boolean acting = AgentSkills.isActive(world, actorId) || AgentInput.isActive(world, actorId);
        if (running) {
            run.finish("Stopped by the operator.");
        }
        AgentSkills.cancel(world, actorId, "Stopped by the operator.");
        AgentInput.cancel(world, actorId);
        return running || acting;
    }

    public static void tick(ServerWorld world) {
        var runs = WORLDS.get(world);
        if (runs == null) {
            return;
        }
        var iterator = runs.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!(world.getEntity(entry.getKey()) instanceof VillagerEntity actor) || !AgentTools.isActor(actor)) {
                entry.getValue().finish("The actor left the loaded world.");
                iterator.remove();
            } else if (entry.getValue().running()) {
                entry.getValue().tick(world, actor);
            }
        }
    }

    public static void clear(ServerWorld world) {
        var runs = WORLDS.remove(world);
        if (runs != null) {
            runs.values().forEach(run -> run.finish("World closed."));
        }
    }

    private record InventoryGoal(Item item, String id, int gain) {
    }

    private static final class Run {
        private final String model;
        private final String provider;
        private final String goal;
        private final int maxTurns;
        private final UUID operator;
        private final JsonObject skillToAdapt;
        private final Map<String, Integer> startingInventory;
        private final ArrayDeque<JsonObject> recent = new ArrayDeque<>();
        private JsonObject demonstration;
        private JsonObject intent;
        private InventoryGoal inventoryGoal;
        private Boolean lastSkillPassed;
        private AgentSkills.Attempt skillFailure;
        private JsonObject failureReport;
        private AgentProgram.CallPath repairTarget;
        private AgentSkills.Ref repairTest;
        private JsonObject rejectedProgram;
        private AgentModelClient client;
        private CompletableFuture<HttpResponse<String>> pending;
        private String detail = "Ready.";
        private int turnsUsed;
        private int nextTurnTick;
        private long requestStarted;
        private boolean ownsSkill;
        private JsonObject pendingTest;

        private Run(AgentModelClient client, String goal, int maxTurns, UUID operator, VillagerEntity actor,
                    JsonObject skill) {
            this.model = client.model();
            this.provider = client.providerLabel();
            this.client = client;
            this.goal = goal;
            this.maxTurns = maxTurns;
            this.operator = operator;
            skillToAdapt = skill;
            pendingTest = skill == null ? null : AgentSkills.Ref.from(skill).json();
            this.startingInventory = AgentInventory.totals(actor.getInventory());
            if (skill != null && skill.has("intent")) {
                intent = skill.getAsJsonObject("intent");
                inventoryGoal = parseInventoryGoal(intent);
            } else if (skill != null) {
                // Older skills can carry the original gain check in their final assertion.
                var program = skill.getAsJsonArray("program");
                var tail = program.get(program.size() - 1).getAsJsonObject();
                if (tail.has("assert") && tail.getAsJsonObject("assert").has("inventory")) {
                    var check = tail.getAsJsonObject("assert").getAsJsonObject("inventory");
                    if (check.has("increase")) {
                        var originalCheck = new JsonObject();
                        originalCheck.add("inventory_item", check.get("item"));
                        originalCheck.add("inventory_gain", check.get("increase"));
                        inventoryGoal = parseInventoryGoal(originalCheck);
                    }
                }
            }
        }

        private boolean running() {
            return client != null;
        }

        private String turnLabel() {
            return turnsUsed + "/" + maxTurns;
        }

        private void tick(ServerWorld world, VillagerEntity actor) {
            try {
                if (pending != null) {
                    if (pending.isDone()) {
                        applyDecision(world, actor);
                    }
                    return;
                }
                if (AgentSkills.isActive(world, actor.getUuid())) {
                    detail = "Waiting for the skill to finish.";
                    return;
                }
                if (ownsSkill) {
                    var result = AgentSkills.status(world, actor.getUuid());
                    var attempt = AgentSkills.completed(world, actor);
                    assessSkill(attempt);
                    var outcome = recent.getLast();
                    String message = attempt.details().get("result").getAsString();
                    outcome.addProperty("ok", lastSkillPassed);
                    outcome.addProperty("message", message);
                    outcome.add("data", result.data());
                    ownsSkill = false;
                    report(world, actor, "run_skill · " + message);
                }
                if (AgentInput.isActive(world, actor.getUuid())) {
                    detail = "Waiting for the current input to finish.";
                    return;
                }
                if (pendingTest != null && (intent != null || skillToAdapt == null)) {
                    var test = pendingTest;
                    pendingTest = null;
                    applyTool(world, actor, new AgentModelClient.Decision("run_skill", test, 0, 0));
                } else if (turnsUsed == maxTurns) {
                    finish(skillFailure == null ? "Turn limit reached."
                            : "Unmet. Turn limit reached after " + AgentSkills.Ref.from(skillFailure.details())
                                    + " failed its test. " + skillFailure.details().get("result").getAsString());
                    report(world, actor, detail, operator);
                } else if (world.getServer().getTicks() - nextTurnTick >= 0) {
                    JsonObject observation = observe(world, actor);
                    requestStarted = System.nanoTime();
                    pending = client.request(observation);
                    turnsUsed++;
                    detail = "Waiting for the model.";
                    LOGGER.info("[Agent loop] {} requested turn {} with {}",
                            actor.getName().getString(), turnLabel(), model);
                }
            } catch (CompletionException failure) {
                requestFailed(world, actor, provider + " connection failed or timed out.");
            } catch (IllegalStateException failure) {
                requestFailed(world, actor, failure.getMessage());
            } catch (RuntimeException failure) {
                finish("The agent loop encountered an error.");
                LOGGER.warn("Agent loop failed with {}", failure.getClass().getSimpleName());
                report(world, actor, detail);
            }
        }

        private void requestFailed(ServerWorld world, VillagerEntity actor, String problem) {
            finish(problem);
            report(world, actor, detail);
        }

        private void assessSkill(AgentSkills.Attempt completed) {
            var attempt = completed.details();
            boolean passed = attempt.get("program_passed").getAsBoolean();
            if (inventoryGoal != null) {
                var evidence = attempt.getAsJsonObject("evidence");
                int before = countInScene(evidence.get("before"));
                int after = countInScene(evidence.get("after"));
                var check = new JsonObject();
                check.addProperty("item", inventoryGoal.id());
                check.addProperty("before", before);
                check.addProperty("after", after);
                check.addProperty("required_gain", inventoryGoal.gain());
                attempt.add("original_inventory_check", check);
                if (passed) {
                    passed = after - before >= inventoryGoal.gain();
                    String detail = passed ? "The original inventory goal passed. "
                            : "The program ended but the original inventory goal was unmet. ";
                    attempt.addProperty("result", detail + inventoryGoal.id() + " went from " + before
                            + " to " + after + ". Required gain " + inventoryGoal.gain() + ".");
                }
            }
            lastSkillPassed = passed;
            skillFailure = passed ? null : completed;
            if (!passed) {
                pendingTest = null;
            }
            failureReport = null;
            repairTarget = null;
            repairTest = null;
        }

        private int countInScene(JsonElement scene) {
            if (scene == null || !scene.isJsonObject()) {
                return 0;
            }
            var amount = scene.getAsJsonObject().getAsJsonObject("inventory").get(inventoryGoal.id());
            return amount == null ? 0 : amount.getAsInt();
        }

        private JsonObject observe(ServerWorld world, VillagerEntity actor) {
            var view = AgentVision.observe(world, actor);
            view.add("inventory", AgentInventory.observedSlots(actor.getInventory()));
            var initial = new JsonObject();
            startingInventory.forEach(initial::addProperty);
            view.add("inventory_at_start", initial);
            if (demonstration != null) {
                view.add("demonstration", demonstration);
            }
            if (intent != null) {
                view.add("intent", intent);
            }
            if (skillToAdapt != null) {
                view.add("skill_to_adapt", skillToAdapt);
            }
            if (skillFailure != null) {
                view.add("skill_failure", skillFailure.details());
                var target = repairTarget == null ? skillFailure.execution().repairTarget(null) : repairTarget;
                if (target.parent() != null) {
                    var selected = AgentSkills.adaptationTarget(world, actor, target.skill());
                    selected.add("call_path", target.lineage());
                    view.add("repair_target", selected);
                }
            }
            if (failureReport != null) {
                view.add("failure_report", failureReport);
            }
            if (rejectedProgram != null) {
                view.add("rejected_program", rejectedProgram);
            }
            if (inventoryGoal != null) {
                view.addProperty("inventory_goal_met", inventoryGain(actor) >= inventoryGoal.gain());
            }
            if (lastSkillPassed != null) {
                view.addProperty("last_skill_passed", lastSkillPassed);
            }
            JsonObject last = recent.peekLast();
            if (last != null && last.has("data")) {
                view.add("tool_result", last.get("data"));
            }
            view.add("skills", AgentSkills.preview(world, actor));
            view.add("demonstrations", AgentDemonstrations.preview(world, actor));
            var outcomes = new JsonArray();
            for (var action : recent) {
                var status = action.deepCopy();
                status.remove("data");
                outcomes.add(status);
            }
            view.add("recent", outcomes);
            view.addProperty("goal", goal);
            view.addProperty("turns_left", maxTurns - turnsUsed);
            return view;
        }

        private void applyDecision(ServerWorld world, VillagerEntity actor) {
            var response = pending;
            pending = null;
            nextTurnTick = world.getServer().getTicks() + TURN_DELAY;
            AgentModelClient.Decision decision;
            try {
                decision = client.readDecision(response.join());
            } catch (AgentModelClient.InvalidToolResponse invalid) {
                var outcome = new JsonObject();
                outcome.addProperty("turn", turnsUsed);
                outcome.addProperty("tool", "model_response");
                outcome.addProperty("ok", false);
                outcome.addProperty("message", invalid.getMessage());
                remember(outcome);
                detail = invalid.getMessage();
                report(world, actor, detail);
                return;
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStarted);
            LOGGER.info("[Agent loop] {} turn {} chose {} ({} input, {} output tokens) in {} ms via {}",
                    actor.getName().getString(), turnLabel(), decision.tool(),
                    decision.inputTokens(), decision.outputTokens(), millis, provider);
            applyTool(world, actor, decision);
        }

        private void applyTool(ServerWorld world, VillagerEntity actor, AgentModelClient.Decision decision) {
            AgentTools.Result result = execute(world, actor, decision);
            if (decision.tool().equals("save_skill")) {
                recordSubmission(world, actor, decision.arguments(), result);
                if (result.ok()) {
                    var published = result.data().getAsJsonObject();
                    if (skillFailure != null) {
                        repairTest = AgentSkills.Ref.from(published);
                        pendingTest = repairTest.json();
                    } else if (intent != null) {
                        AgentSkills.rememberIntent(world, actor, published, intent);
                    }
                }
            }
            ownsSkill = decision.tool().equals("run_skill") && result.ok();
            if (!result.ok()) {
                LOGGER.info("[Agent loop] {} tool {} rejected. {}", actor.getName().getString(), decision.tool(), result.message());
            }
            var outcome = new JsonObject();
            outcome.addProperty("turn", turnsUsed);
            outcome.addProperty("tool", decision.tool());
            if (decision.arguments().toString().length() <= 240) {
                outcome.add("arguments", decision.arguments());
            }
            outcome.addProperty("ok", result.ok());
            outcome.addProperty("message", result.message());
            if (decision.tool().equals("read_demo") && result.ok() && result.data().isJsonObject()) {
                var inspected = result.data().getAsJsonObject();
                if (skillToAdapt == null && skillFailure == null && !inspected.get("name").getAsString().equals(demoName())) {
                    intent = null;
                    inventoryGoal = null;
                    lastSkillPassed = null;
                }
                demonstration = inspected.deepCopy();
            } else if (!result.data().isJsonNull()) {
                outcome.add("data", result.data());
            }
            remember(outcome);
            detail = decision.tool() + " · " + result.message();
            boolean reportToOperator = result.ok() && (decision.tool().equals("report_intent")
                    || decision.tool().equals("report_failure") || decision.tool().equals("finish"));
            report(world, actor, detail, reportToOperator ? operator : null);
            if (result.ok() && decision.tool().equals("finish")) {
                finish(detail);
            }
        }

        private String demoName() {
            if (demonstration != null) {
                return demonstration.get("name").getAsString();
            }
            return skillToAdapt != null && skillToAdapt.has("demo") ? skillToAdapt.get("demo").getAsString() : "";
        }

        private void recordSubmission(ServerWorld world, VillagerEntity actor, JsonObject args, AgentTools.Result result) {
            AgentDebug.submitted(world, actor.getUuid(), args, result);
            var program = args.get("program");
            rejectedProgram = null;
            if (!result.ok() && program != null) {
                String source = AgentProgram.source(program);
                rejectedProgram = new JsonObject();
                if (source.length() <= AgentProgram.MAX_SOURCE_LENGTH) {
                    rejectedProgram.add("program", program);
                } else {
                    rejectedProgram.addProperty("program_preview", source.substring(0, AgentProgram.MAX_SOURCE_LENGTH));
                }
                rejectedProgram.addProperty("error", result.message());
            }
        }

        private AgentTools.Result execute(ServerWorld world, VillagerEntity actor, AgentModelClient.Decision decision) {
            String tool = decision.tool();
            JsonObject args = decision.arguments();
            try {
                if (tool.equals("report_intent")) {
                    AgentTools.validate(AgentModelClient.REPORT_INTENT, args);
                    AgentTools.require(intent == null, "Purpose already reported. Use the live view to test the intended result.");
                    String purpose = AgentTools.boundedText(args, "purpose", 260, true);
                    String evidence = AgentTools.boundedText(args, "evidence", 480, true);
                    String check = AgentTools.boundedText(args, "success_check", 260, true);
                    var proposed = parseInventoryGoal(args);
                    AgentTools.require(inventoryGoal == null || proposed == null || inventoryGoal.equals(proposed),
                            "Keep the original inventory item and required gain.");
                    if (inventoryGoal == null) {
                        inventoryGoal = proposed;
                    }
                    intent = args.deepCopy();
                    if (inventoryGoal != null) {
                        intent.addProperty("inventory_item", inventoryGoal.id());
                        intent.addProperty("inventory_gain", inventoryGoal.gain());
                    }
                    if (skillFailure != null) {
                        assessSkill(skillFailure);
                    }
                    if (demonstration != null) {
                        demonstration.remove("frames");
                        demonstration.remove("initial_view");
                        demonstration.remove("final_view");
                        demonstration.remove("intermediate_views_sampled");
                    }
                    return AgentTools.ok("Purpose — " + purpose + " · Evidence — " + evidence + " · Check — " + check, null);
                }
                if (tool.equals("report_failure")) {
                    AgentTools.validate(AgentModelClient.REPORT_FAILURE, args);
                    AgentTools.require(skillFailure != null && failureReport == null, "No unreported skill failure.");
                    AgentTools.require(skillToAdapt == null || intent != null, "Report the original purpose first.");
                    String cause = AgentTools.boundedText(args, "cause", 260, true);
                    String evidence = AgentTools.boundedText(args, "evidence", 480, true);
                    String adjustment = AgentTools.boundedText(args, "adjustment", 480, true);
                    Integer callId = args.has("call_id") ? AgentTools.number(args, "call_id") : null;
                    repairTarget = skillFailure.execution().repairTarget(callId);
                    failureReport = args.deepCopy();
                    return AgentTools.ok("Repair " + repairTarget.skill() + " · Likely cause — " + cause + " · Evidence — " + evidence
                            + " · Adjustment — " + adjustment, null);
                }
                if (tool.equals("read_demo") && args.has("name")) {
                    var name = args.get("name");
                    AgentTools.require(demonstration == null || !name.isJsonPrimitive() || !name.getAsString().equals(demoName()),
                            "This entire demonstration has already been read. Use the purpose and current view.");
                    if (skillToAdapt != null) {
                        AgentTools.require(intent == null && name.isJsonPrimitive() && name.getAsString().equals(demoName()),
                                "Use the recording linked to the original skill.");
                    }
                }
                if ((demonstration != null || skillToAdapt != null) && intent == null) {
                    AgentTools.require(!AgentTools.isInputPrimitive(tool) && !tool.equals("save_skill")
                                    && !tool.equals("run_skill") && !tool.equals("finish"),
                            "Report the demonstrated purpose and success check with report_intent first.");
                }
                if (skillFailure != null && failureReport == null) {
                    AgentTools.require(!AgentTools.isInputPrimitive(tool) && !tool.equals("save_skill")
                                    && !tool.equals("run_skill") && !tool.equals("finish"),
                            "Use report_failure to explain the failed test and what you will adjust first.");
                }
                if (tool.equals("save_skill") && skillFailure != null) {
                    AgentTools.validate(AgentTools.descriptor(tool), args);
                    AgentTools.require(!args.has("demo"), "Omit demo when revising the selected skill.");
                    return AgentSkills.repair(world, actor, args, repairTarget, intent);
                }
                if (tool.equals("save_skill") && args.has("demo")) {
                    var link = args.get("demo");
                    AgentTools.require(link.isJsonPrimitive() && link.getAsJsonPrimitive().isString()
                                    && link.getAsString().equals(demoName()),
                            "Read the demonstration linked to this skill and report its purpose first.");
                }
                if (tool.equals("save_skill") && intent != null && !args.has("demo") && !demoName().isEmpty()) {
                    args = args.deepCopy();
                    args.addProperty("demo", demoName());
                }
                if (tool.equals("run_skill") && skillFailure != null) {
                    AgentTools.validate(AgentTools.descriptor(tool), args);
                    AgentTools.require(repairTest != null && args.has("version") && repairTest.equals(AgentSkills.Ref.from(args)),
                            "Save a revision of the selected skill to retest the full program.");
                }
                if (tool.equals("finish")) {
                    AgentTools.validate(AgentModelClient.FINISH, args);
                    String evidence = AgentTools.boundedText(args, "evidence", 480, true);
                    String outcome = args.get("outcome").getAsString();
                    if (outcome.equals("verified")) {
                        if (inventoryGoal != null && inventoryGain(actor) < inventoryGoal.gain()) {
                            int before = startingInventory.getOrDefault(inventoryGoal.id(), 0);
                            throw new AgentTools.ToolFailure("Started with " + before + " " + inventoryGoal.id()
                                    + " and currently has " + (before + inventoryGain(actor)) + ". The required gain is "
                                    + inventoryGoal.gain() + ".");
                        }
                        AgentTools.require(!Boolean.FALSE.equals(lastSkillPassed),
                                "The latest skill test failed. Revise and retest it, or report the goal unmet.");
                        AgentTools.require(skillToAdapt == null || Boolean.TRUE.equals(lastSkillPassed),
                                "Test the skill against its original goal before reporting success.");
                    }
                    String label = switch (outcome) {
                        case "verified" -> "Verified";
                        case "unmet" -> "Unmet";
                        case "uncertain" -> "Uncertain";
                        default -> throw new AgentTools.ToolFailure("Use verified, unmet, or uncertain as the outcome.");
                    };
                    return AgentTools.ok(label + " · " + evidence, null);
                }
                return AgentTools.call(world, actor, tool, args, false);
            } catch (AgentTools.ToolFailure failure) {
                return AgentTools.error(failure.getMessage());
            }
        }

        private static InventoryGoal parseInventoryGoal(JsonObject args) {
            AgentTools.require(args.has("inventory_item") == args.has("inventory_gain"),
                    "Supply both inventory_item and inventory_gain for a pickup check.");
            if (!args.has("inventory_item")) {
                return null;
            }
            var id = Identifier.tryParse(args.get("inventory_item").getAsString());
            AgentTools.require(id != null && Registries.ITEM.containsId(id), "Use a known Minecraft item ID.");
            int gain = AgentTools.number(args, "inventory_gain");
            AgentTools.require(gain > 0, "Use a positive inventory gain.");
            return new InventoryGoal(Registries.ITEM.get(id), id.toString(), gain);
        }

        private int inventoryGain(VillagerEntity actor) {
            return AgentInventory.count(actor.getInventory(), inventoryGoal.item())
                    - startingInventory.getOrDefault(inventoryGoal.id(), 0);
        }

        private void remember(JsonObject outcome) {
            if (!recent.isEmpty()) {
                recent.getLast().remove("data");
            }
            if (recent.size() == HISTORY_LIMIT) {
                recent.removeFirst();
            }
            recent.addLast(outcome);
        }

        private void finish(String message) {
            if (pending != null) {
                pending.cancel(true);
                pending = null;
            }
            client = null;
            detail = message;
        }
    }

    private static void report(ServerWorld world, VillagerEntity actor, String detail) {
        report(world, actor, detail, null);
    }

    private static void report(ServerWorld world, VillagerEntity actor, String detail, UUID operator) {
        String message = actor.getName().getString() + " · " + detail;
        LOGGER.info("[Agent loop] {}", message);
        var recipient = operator == null ? null : world.getServer().getPlayerManager().getPlayer(operator);
        if (recipient != null) {
            recipient.sendMessage(Text.literal("[Agent loop] " + message));
            return;
        }
        for (var player : world.getPlayers(player -> player.squaredDistanceTo(actor) <= 256)) {
            player.sendMessage(Text.literal("[Agent loop] " + message));
        }
    }
}
