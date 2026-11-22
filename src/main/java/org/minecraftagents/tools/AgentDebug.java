package org.minecraftagents.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Operator snapshots of submitted source and the latest input program run. */
final class AgentDebug {
    private static final int RECENT_LIMIT = 14;
    private static final Map<ServerWorld, Map<UUID, State>> WORLDS = new HashMap<>();

    private AgentDebug() {
    }

    static State state(ServerWorld world, UUID id) {
        return WORLDS.computeIfAbsent(world, key -> new HashMap<>()).computeIfAbsent(id, key -> new State());
    }

    static void submitted(ServerWorld world, UUID id, JsonObject args, AgentTools.Result result) {
        var program = args.get("program");
        if (program == null) {
            return;
        }
        var name = args.get("name");
        String label = name != null && name.isJsonPrimitive() ? name.getAsString() : "(unnamed)";
        String status = result.ok() ? result.message() : "Rejected. " + result.message();
        state(world, id).draft = new Plan(label, status, AgentProgram.source(program));
    }

    static void clear(ServerWorld world) {
        WORLDS.remove(world);
    }

    static void effect(ServerWorld world, VillagerEntity actor, String detail) {
        var state = WORLDS.getOrDefault(world, Map.of()).get(actor.getUuid());
        if (state != null && state.active) {
            if (state.effects.size() == RECENT_LIMIT) {
                state.effects.remove(0);
            }
            state.effects.add(detail);
            state.event(world, actor, detail);
        }
    }

    record Plan(String label, String status, String source) {
    }

    static final class State {
        Plan draft;
        Plan run;
        boolean tracing;
        final List<String> events = new ArrayList<>();
        private final ArrayDeque<String> recent = new ArrayDeque<>();
        private final List<String> effects = new ArrayList<>();
        private JsonObject before;
        private JsonObject after;
        private boolean active;
        private int startTick;

        void started(ServerWorld world, VillagerEntity actor, String label, String source) {
            run = new Plan(label, "Running.", source);
            active = true;
            startTick = world.getServer().getTicks();
            before = scene(world, actor);
            after = null;
            events.clear();
            recent.clear();
            effects.clear();
            event(world, actor, "Started " + label + ".");
        }

        void finished(ServerWorld world, VillagerEntity actor, boolean success, String detail) {
            String status = (success ? "Passed. " : "Failed. ") + detail;
            run = new Plan(run.label(), status, run.source());
            event(world, actor, status);
            active = false;
            after = actor == null ? null : scene(world, actor);
        }

        void event(ServerWorld world, VillagerEntity actor, String detail) {
            String position = actor == null ? "" : String.format(Locale.ROOT,
                    " (%.2f, %.2f, %.2f) yaw=%.0f pitch=%.0f",
                    actor.getX(), actor.getY(), actor.getZ(), actor.getYaw(), actor.getPitch());
            String entry = "+" + (world.getServer().getTicks() - startTick) + "t" + position + " " + detail;
            if (recent.size() == RECENT_LIMIT) {
                recent.removeFirst();
            }
            recent.addLast(entry);
            if (tracing) {
                events.add(entry);
            }
        }

        JsonObject evidence() {
            var data = new JsonObject();
            data.add("before", before);
            data.add("after", after);
            var steps = new JsonArray();
            recent.forEach(steps::add);
            data.add("recent_steps", steps);
            var changes = new JsonArray();
            effects.forEach(changes::add);
            data.add("world_changes", changes);
            return data;
        }

        private static JsonObject scene(ServerWorld world, VillagerEntity actor) {
            var data = AgentVision.observe(world, actor);
            trim(data.getAsJsonArray("blocks"), 8);
            trim(data.getAsJsonArray("entities"), 6);
            data.addProperty("position", actor.getPos().toString());
            var inventory = new JsonObject();
            AgentInventory.totals(actor.getInventory()).forEach(inventory::addProperty);
            data.add("inventory", inventory);
            return data;
        }

        private static void trim(JsonArray array, int limit) {
            while (array.size() > limit) {
                array.remove(array.size() - 1);
            }
        }
    }
}
