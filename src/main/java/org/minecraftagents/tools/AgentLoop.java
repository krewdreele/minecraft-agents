package org.minecraftagents.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Collections;
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
    private static final int TRIAL_DAY_DELAY = 160;
    private static final int TRIAL_NIGHT_DELAY = 80;
    private static final int TRIAL_RETRY_DELAY = 400;
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentLoop.class);
    private static final Map<ServerWorld, Map<UUID, Run>> WORLDS = Collections.synchronizedMap(new HashMap<>());

    private AgentLoop() {
    }

    static String start(ServerWorld world, UUID actorId, int turns, String goal, AgentModelClient.Provider provider) {
        return start(world, actorId, turns, goal, AgentModelClient.load(provider));
    }

    static void startTrial(ServerWorld world, UUID actorId, AgentModelClient client, String goal) {
        start(world, actorId, 0, goal, client);
    }

    static boolean isRunning(ServerWorld world, UUID actorId) {
        var runs = WORLDS.get(world);
        Run run = runs == null ? null : runs.get(actorId);
        return run != null && run.running();
    }

    private static String start(ServerWorld world, UUID actorId, int turns, String goal, AgentModelClient client) {
        if (!(world.getEntity(actorId) instanceof VillagerEntity actor) || !AgentTools.isActor(actor)) {
            throw new IllegalStateException("The actor is unavailable in this world.");
        }
        if (goal.isBlank() || goal.length() > 200) {
            throw new IllegalStateException("Use a goal of 1 to 200 characters.");
        }
        var runs = WORLDS.computeIfAbsent(world, key -> new HashMap<>());
        Run previous = runs.get(actorId);
        if (previous != null && previous.running()) {
            throw new IllegalStateException("This actor already has an active run.");
        }
        var run = new Run(client, goal, turns);
        runs.put(actorId, run);
        return "Started " + actor.getName().getString() + " with " + run.model
                + (turns == 0 ? " for the trial." : " for up to " + turns + " turns.");
    }

    static String status(ServerWorld world, UUID actorId, boolean stop) {
        var runs = WORLDS.get(world);
        Run run = runs == null ? null : runs.get(actorId);
        if (run == null) {
            return "This actor has no loop status.";
        }
        if (stop && run.running()) {
            if (run.ownsSkill) {
                AgentSkills.cancel(world, actorId, "Stopped by the operator.");
            }
            if (run.ownsAttack) {
                AgentCombat.cancel(world, actorId);
            }
            if (run.ownsRoute) {
                AgentNavigation.cancel(world, actorId);
            }
            run.finish("Stopped by the operator.");
        }
        return run.model + " · " + run.turnLabel() + " turns. " + run.detail;
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

    private static final class Run {
        private final String model;
        private final String provider;
        private final String goal;
        private final int maxTurns;
        private final ArrayDeque<JsonObject> recent = new ArrayDeque<>();
        private AgentModelClient client;
        private CompletableFuture<HttpResponse<String>> pending;
        private String detail = "Ready.";
        private int turnsUsed;
        private int nextTurnTick;
        private long requestStarted;
        private boolean ownsAttack;
        private boolean ownsRoute;
        private boolean ownsSkill;

        private Run(AgentModelClient client, String goal, int maxTurns) {
            this.model = client.model();
            this.provider = client.providerLabel();
            this.client = client;
            this.goal = goal;
            this.maxTurns = maxTurns;
        }

        private boolean running() {
            return client != null;
        }

        private boolean continuous() {
            return maxTurns == 0;
        }

        private String turnLabel() {
            return continuous() ? Integer.toString(turnsUsed) : turnsUsed + "/" + maxTurns;
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
                    var outcome = recent.getLast();
                    outcome.addProperty("ok", result.ok());
                    outcome.addProperty("message", result.message());
                    outcome.add("data", result.data());
                    if (result.ok()) {
                        AgentMemory.get(world).rememberAction(actor.getUuid(), "run_skill", result.message(), world.getTime());
                    }
                    ownsSkill = false;
                    report(world, actor, "run_skill · " + result.message());
                }
                if (AgentCombat.isActive(world, actor.getUuid())) {
                    detail = "Waiting for the attack to finish.";
                    return;
                }
                if (AgentNavigation.isActive(world, actor.getUuid())) {
                    detail = "Walking to the destination.";
                    return;
                }
                if (ownsRoute) {
                    var route = AgentNavigation.status(world, actor.getUuid());
                    JsonObject outcome = recent.getLast();
                    outcome.addProperty("ok", route != null && route.completed());
                    outcome.addProperty("message", route == null ? "The route ended." : route.message());
                    var data = outcome.getAsJsonObject("data");
                    if (route != null && data != null) {
                        data.addProperty("steps", route.steps());
                        data.addProperty("active", false);
                        data.addProperty("completed", route.completed());
                        data.remove("message");
                    }
                    if (route != null && route.totalSteps() > 0 && !outcome.get("tool").getAsString().equals("go_to")) {
                        report(world, actor, outcome.get("tool").getAsString() + " · " + route.message());
                    }
                    if (route != null && route.completed() && outcome.get("tool").getAsString().equals("go_to")
                            && outcome.has("arguments") && outcome.getAsJsonObject("arguments").has("target")) {
                        AgentMemory.get(world).rememberAction(actor.getUuid(), "go_to", route.message(), world.getTime());
                    }
                }
                if (ownsAttack) {
                    var attack = AgentCombat.status(world, actor.getUuid());
                    JsonObject outcome = recent.getLast();
                    outcome.addProperty("ok", attack != null && attack.hits() > 0);
                    if (attack != null) {
                        outcome.addProperty("message", attack.message() + " Hits: " + attack.hits() + ".");
                        if (attack.hits() > 0) {
                            AgentMemory.get(world).rememberAction(actor.getUuid(), "attack",
                                    outcome.get("message").getAsString(), world.getTime());
                        }
                        var data = outcome.getAsJsonObject("data");
                        if (data != null) {
                            data.addProperty("hits", attack.hits());
                            data.addProperty("swings", attack.swings());
                            data.addProperty("active", false);
                            data.remove("message");
                        }
                    }
                }
                ownsAttack = false;
                ownsRoute = false;
                if (!continuous() && turnsUsed == maxTurns) {
                    finish("Turn limit reached.");
                    report(world, actor, detail);
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
            if (continuous()) {
                pending = null;
                nextTurnTick = world.getServer().getTicks() + TRIAL_RETRY_DELAY;
                detail = problem + " Retrying in 20 seconds.";
            } else {
                finish(problem);
            }
            report(world, actor, detail);
        }

        private JsonObject observe(ServerWorld world, VillagerEntity actor) {
            UUID id = actor.getUuid();
            JsonObject view = AgentTools.localObservation(world, actor);
            view.add("entities", nearEntities(view.getAsJsonArray("entities")));
            JsonObject mapArgs = new JsonObject();
            JsonElement requestedNotes = null;
            JsonElement recipePage = null;
            JsonElement recalled = null;
            JsonElement skillPage = null;
            JsonObject last = recent.peekLast();
            if (last != null && last.get("ok").getAsBoolean()) {
                String tool = last.get("tool").getAsString();
                if (tool.equals("map")) {
                    mapArgs = last.getAsJsonObject("arguments");
                } else if (tool.equals("read_knowledge")) {
                    requestedNotes = last.remove("data");
                } else if (tool.equals("recipe_book")) {
                    recipePage = last.remove("data");
                } else if (tool.equals("recall")) {
                    recalled = last.remove("data");
                } else if (tool.equals("find_skills")) {
                    skillPage = last.remove("data");
                }
            }
            view.add("map", AgentTools.call(world, id, "map", mapArgs).data());
            view.add("inventory", AgentTools.call(world, id, "inventory", new JsonObject()).data());
            view.add("crafting", AgentTools.craftingSnapshot(world, actor));
            if (recipePage != null) {
                view.add("recipe_page", recipePage);
            }
            view.add("inbox", recentMessages(AgentTools.call(world, id, "read_messages", new JsonObject()).data().getAsJsonArray()));
            view.add("knowledge", requestedNotes == null ? AgentTools.knowledgeSnapshot(world) : requestedNotes);
            view.add("memory", AgentTools.memorySnapshot(world, id, goal));
            view.add("skills", AgentSkills.preview(world));
            if (skillPage != null) {
                view.add("skill_page", skillPage);
            }
            if (recalled != null) {
                view.add("recalled", recalled);
            }
            var outcomes = new JsonArray();
            recent.forEach(outcomes::add);
            view.add("recent", outcomes);
            view.addProperty("actor", actor.getName().getString());
            view.addProperty("health", actor.getHealth());
            view.addProperty("max_health", actor.getMaxHealth());
            view.addProperty("day_tick", Math.floorMod(world.getTimeOfDay(), 24_000L));
            view.addProperty("night", world.isNight());
            view.addProperty("goal", goal);
            if (continuous()) {
                view.addProperty("trial_ticks_left", AgentTrial.remainingTicks(world));
            } else {
                view.addProperty("turns_left", maxTurns - turnsUsed);
            }
            return view;
        }

        private void applyDecision(ServerWorld world, VillagerEntity actor) {
            var response = pending;
            pending = null;
            var decision = client.readDecision(response.join());
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStarted);
            LOGGER.info("[Agent loop] {} turn {} chose {} ({} input, {} output tokens) in {} ms via {}",
                    actor.getName().getString(), turnLabel(), decision.tool(),
                    decision.inputTokens(), decision.outputTokens(), millis, provider);
            int delay = continuous() ? (world.isNight() ? TRIAL_NIGHT_DELAY : TRIAL_DAY_DELAY) : TURN_DELAY;
            nextTurnTick = world.getServer().getTicks() + delay;
            if (decision.tool().equals("finish")) {
                if (continuous()) {
                    detail = "Model paused. The trial will request another decision.";
                    var outcome = new JsonObject();
                    outcome.addProperty("turn", turnsUsed);
                    outcome.addProperty("tool", "finish");
                    outcome.addProperty("ok", true);
                    outcome.addProperty("message", detail);
                    remember(outcome);
                } else {
                    finish("Model ended the run.");
                }
                report(world, actor, detail);
                return;
            }

            AgentTools.Result result = AgentTools.call(world, actor.getUuid(), decision.tool(), decision.arguments());
            ownsSkill = decision.tool().equals("run_skill") && AgentTools.pending(decision.tool(), result);
            ownsAttack = decision.tool().equals("attack") && result.ok();
            ownsRoute = result.ok() && switch (decision.tool()) {
                case "go_to", "gather", "retreat" -> result.data().getAsJsonObject().get("active").getAsBoolean();
                default -> false;
            };
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
            if (result.ok() && decision.tool().equals("look") && decision.arguments().has("block")) {
                var positions = new JsonArray();
                var blocks = result.data().getAsJsonObject().getAsJsonArray("blocks");
                for (int index = 0; index < Math.min(6, blocks.size()); index++) {
                    positions.add(blocks.get(index).getAsJsonObject().get("position"));
                }
                outcome.add("positions", positions);
            } else if (!result.data().isJsonNull()
                    && (decision.tool().equals("read_knowledge") || decision.tool().equals("recipe_book")
                        || decision.tool().equals("recall")
                        || decision.tool().equals("find_skills")
                        || result.data().toString().length() <= 360)) {
                outcome.add("data", result.data());
            }
            remember(outcome);
            detail = decision.tool() + " · " + result.message();
            report(world, actor, detail);
        }

        private void remember(JsonObject outcome) {
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

    private static JsonArray nearEntities(JsonArray entities) {
        var sample = new JsonArray();
        for (int index = 0; index < Math.min(6, entities.size()); index++) {
            JsonObject entity = entities.get(index).getAsJsonObject().deepCopy();
            if (!entity.get("kind").getAsString().equals("actor")) {
                entity.remove("uuid");
            }
            sample.add(entity);
        }
        return sample;
    }

    private static JsonArray recentMessages(JsonArray inbox) {
        var sample = new JsonArray();
        for (int index = Math.max(0, inbox.size() - 2); index < inbox.size(); index++) {
            JsonObject message = inbox.get(index).getAsJsonObject().deepCopy();
            message.remove("sender");
            sample.add(message);
        }
        return sample;
    }

    private static void report(ServerWorld world, VillagerEntity actor, String detail) {
        String message = actor.getName().getString() + " · " + detail;
        LOGGER.info("[Agent loop] {}", message);
        for (var player : world.getPlayers(player -> player.squaredDistanceTo(actor) <= 256)) {
            player.sendMessage(Text.literal("[Agent loop] " + message));
        }
    }
}
