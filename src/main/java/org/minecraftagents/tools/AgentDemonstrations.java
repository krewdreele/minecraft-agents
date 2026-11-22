package org.minecraftagents.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.minecraftagents.tools.AgentTools.require;

/** Saved inputs and observed world changes from a demonstrator. */
public final class AgentDemonstrations extends PersistentState {
    private static final int MAX_DEMOS = 32;
    private static final int MAX_FRAMES = 160;
    private static final int MAX_TICKS = 2400;
    private static final int FULL_VIEW_CHAR_LIMIT = 18_000;
    private static final int MODEL_CHECKPOINTS = 5;
    private static final Gson GSON = new Gson();
    private static final Map<ServerWorld, Map<UUID, Recording>> ACTIVE = new HashMap<>();
    private static final Set<Subject> HUMAN_REQUESTS = ConcurrentHashMap.newKeySet();
    private final Map<String, JsonObject> demos = new LinkedHashMap<>();

    private record Subject(MinecraftServer server, UUID player) {
    }

    private static final class Recording {
        private final AgentDemonstrations store;
        private final JsonObject demo;
        private final long started;
        private final boolean human;
        private float yaw;
        private float pitch;
        private int slot;
        private JsonObject segment;
        private long lastSampleTick;
        private Map<String, Integer> inventory;

        private Recording(AgentDemonstrations store, JsonObject demo, ServerWorld world, Entity subject) {
            this.store = store;
            this.demo = demo;
            started = world.getTime();
            human = subject instanceof ServerPlayerEntity;
            yaw = subject.getYaw();
            pitch = subject.getPitch();
            slot = human ? ((ServerPlayerEntity) subject).getInventory().selectedSlot : -1;
            inventory = inventoryOf(subject);
        }

        private JsonArray frames() {
            return demo.getAsJsonArray("frames");
        }
    }

    private static AgentDemonstrations get(ServerWorld world, Entity subject) {
        return world.getServer().getOverworld().getPersistentStateManager().getOrCreate(
                AgentDemonstrations::readNbt, AgentDemonstrations::new,
                AgentArena.libraryId(world, subject, "minecraft_agents_demonstrations"));
    }

    static String start(ServerWorld world, Entity subject, String name) {
        require(name.matches("[a-z][a-z0-9_]{0,31}"), "Use a short snake_case demonstration name.");
        var recordings = ACTIVE.computeIfAbsent(world, key -> new HashMap<>());
        require(!recordings.containsKey(subject.getUuid()), "This subject is already recording.");
        var state = get(world, subject);
        require(!state.demos.containsKey(name), "A demonstration with that name already exists.");
        require(state.demos.size() < MAX_DEMOS, "The demonstration library is full.");
        var demo = new JsonObject();
        demo.addProperty("name", name);
        demo.addProperty("subject", subject.getName().getString());
        demo.addProperty("kind", subject instanceof ServerPlayerEntity ? "player" : "actor");
        demo.add("initial_view", AgentVision.observe(world, subject));
        demo.add("frames", new JsonArray());
        demo.add("inventory_changes", new JsonArray());
        var recording = new Recording(state, demo, world, subject);
        demo.add("initial_inventory", GSON.toJsonTree(recording.inventory));
        state.demos.put(name, demo);
        state.markDirty();
        recordings.put(subject.getUuid(), recording);
        if (recording.human) {
            HUMAN_REQUESTS.add(new Subject(world.getServer(), subject.getUuid()));
        }
        return "Recording " + name + " from " + subject.getName().getString() + ".";
    }

    static String stop(ServerWorld world, UUID id) {
        var recordings = ACTIVE.get(world);
        var recording = recordings == null ? null : recordings.remove(id);
        require(recording != null, "This subject has no active recording.");
        if (recording.human) {
            HUMAN_REQUESTS.remove(new Subject(world.getServer(), id));
        }
        var subject = world.getEntity(id);
        recording.demo.addProperty("elapsed_ticks", world.getTime() - recording.started);
        if (subject != null && subject.isAlive()) {
            recording.demo.add("final_view", AgentVision.observe(world, subject));
            observeInventory(world, subject, recording);
            recording.demo.add("final_inventory", GSON.toJsonTree(recording.inventory));
        }
        recording.store.markDirty();
        String name = recording.demo.get("name").getAsString();
        return "Saved " + name + " with " + recording.frames().size() + " input segments.";
    }

    public static boolean wantsPlayerInput(MinecraftServer server, UUID player) {
        return HUMAN_REQUESTS.contains(new Subject(server, player));
    }

    public static void playerInput(ServerPlayerEntity player, String keys, String buttons, float yaw, float pitch,
                                   int selectedSlot) {
        var world = player.getServerWorld();
        var recording = recording(world, player.getUuid());
        if (recording == null || !recording.human || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return;
        }
        float turn = MathHelper.wrapDegrees(yaw - recording.yaw);
        float tilt = pitch - recording.pitch;
        recording.yaw = yaw;
        recording.pitch = pitch;
        boolean changedSlot = selectedSlot != recording.slot;
        recording.slot = selectedSlot;
        long tick = world.getTime();
        if (keys.isEmpty() && buttons.isEmpty() && turn == 0 && tilt == 0 && !changedSlot) {
            recording.segment = null;
            return;
        }
        var segment = recording.segment;
        if (segment != null && tick - recording.lastSampleTick <= 1 && !changedSlot
                && segment.get("ticks").getAsInt() < 20 && segment.get("keys").getAsString().equals(keys)
                && segment.get("buttons").getAsString().equals(buttons)) {
            segment.addProperty("ticks", segment.get("ticks").getAsInt() + 1);
            segment.addProperty("yaw", segment.get("yaw").getAsFloat() + turn);
            segment.addProperty("pitch", segment.get("pitch").getAsFloat() + tilt);
            recording.store.markDirty();
        } else {
            segment = new JsonObject();
            segment.addProperty("keys", keys);
            segment.addProperty("buttons", buttons);
            segment.addProperty("yaw", turn);
            segment.addProperty("pitch", tilt);
            segment.addProperty("slot", selectedSlot + 1);
            segment.addProperty("ticks", 1);
            recording.segment = segment;
            add(world, player.getUuid(), recording, segment, AgentVision.observe(world, player, yaw, pitch));
        }
        recording.lastSampleTick = tick;
    }

    static void agentInput(ServerWorld world, VillagerEntity actor, String tool, JsonObject args) {
        var recording = recording(world, actor.getUuid());
        if (recording == null) {
            return;
        }
        var segment = new JsonObject();
        segment.addProperty("tool", tool);
        segment.add("args", args.deepCopy());
        add(world, actor.getUuid(), recording, segment, AgentVision.observe(world, actor));
    }

    private static void add(ServerWorld world, UUID id, Recording recording, JsonObject frame, JsonObject view) {
        frame.addProperty("tick", world.getTime() - recording.started);
        frame.add("view", view);
        recording.frames().add(frame);
        recording.store.markDirty();
        if (recording.frames().size() == MAX_FRAMES) {
            stop(world, id);
        }
    }

    private static Recording recording(ServerWorld world, UUID id) {
        return ACTIVE.getOrDefault(world, Map.of()).get(id);
    }

    private static Map<String, Integer> inventoryOf(Entity subject) {
        return AgentInventory.totals(subject instanceof ServerPlayerEntity player
                ? player.getInventory() : ((VillagerEntity) subject).getInventory());
    }

    private static void observeInventory(ServerWorld world, Entity subject, Recording recording) {
        var current = inventoryOf(subject);
        if (current.equals(recording.inventory)) {
            return;
        }
        var changes = recording.demo.getAsJsonArray("inventory_changes");
        if (changes.size() < MAX_FRAMES) {
            var items = new LinkedHashSet<>(recording.inventory.keySet());
            items.addAll(current.keySet());
            var delta = new JsonObject();
            for (String item : items) {
                int difference = current.getOrDefault(item, 0) - recording.inventory.getOrDefault(item, 0);
                if (difference != 0) {
                    delta.addProperty(item, difference);
                }
            }
            var event = new JsonObject();
            event.addProperty("tick", world.getTime() - recording.started);
            event.add("items", delta);
            changes.add(event);
        }
        recording.inventory = current;
        recording.store.markDirty();
    }

    public static void tick(ServerWorld world) {
        var active = ACTIVE.get(world);
        if (active == null) {
            return;
        }
        for (var id : new ArrayList<>(active.keySet())) {
            var recording = active.get(id);
            var entity = world.getEntity(id);
            if (entity == null || !entity.isAlive() || world.getTime() - recording.started >= MAX_TICKS) {
                stop(world, id);
            } else {
                observeInventory(world, entity, recording);
                if (recording.demo.getAsJsonArray("inventory_changes").size() == MAX_FRAMES) {
                    stop(world, id);
                }
            }
        }
    }

    public static void clear(ServerWorld world) {
        var active = ACTIVE.remove(world);
        if (active != null) {
            active.keySet().forEach(id -> HUMAN_REQUESTS.remove(new Subject(world.getServer(), id)));
        }
    }

    static boolean exists(ServerWorld world, VillagerEntity actor, String name) {
        return get(world, actor).demos.containsKey(name);
    }

    static JsonArray preview(ServerWorld world, VillagerEntity actor) {
        var recent = new ArrayList<>(get(world, actor).demos.values());
        var result = new JsonArray();
        for (int index = recent.size() - 1; index >= Math.max(0, recent.size() - 5); index--) {
            result.add(summary(recent.get(index)));
        }
        return result;
    }

    static AgentTools.Result read(ServerWorld world, VillagerEntity actor, JsonObject args) {
        var demos = get(world, actor).demos;
        if (!args.has("name")) {
            var result = new JsonArray();
            demos.values().forEach(demo -> result.add(summary(demo)));
            return AgentTools.ok("Saved demonstrations.", result);
        }
        String name = AgentTools.boundedText(args, "name", 32, true);
        var demo = demos.get(name);
        require(demo != null, "No demonstration named " + name + ".");
        var frames = demo.getAsJsonArray("frames");
        var result = header(demo);
        if (demo.has("inventory_changes")) {
            result.add("inventory_changes", demo.get("inventory_changes"));
        }
        if (demo.toString().length() <= FULL_VIEW_CHAR_LIMIT) {
            result.add("frames", frames);
        } else {
            var sequence = new JsonArray();
            int stride = Math.max(1, (frames.size() + MODEL_CHECKPOINTS - 1) / MODEL_CHECKPOINTS);
            for (int index = 0; index < frames.size(); index++) {
                var source = frames.get(index).getAsJsonObject();
                var step = new JsonObject();
                for (var field : source.entrySet()) {
                    if (!field.getKey().equals("view") || index % stride == 0) {
                        step.add(field.getKey(), field.getValue());
                    }
                }
                sequence.add(step);
            }
            result.add("frames", sequence);
            result.addProperty("intermediate_views_sampled", true);
        }
        return AgentTools.ok("Demonstration " + name + ".", result);
    }

    private static JsonObject header(JsonObject demo) {
        var header = summary(demo);
        for (String key : List.of("initial_view", "final_view", "initial_inventory", "final_inventory", "elapsed_ticks")) {
            if (demo.has(key)) {
                header.add(key, demo.get(key));
            }
        }
        return header;
    }

    private static JsonObject summary(JsonObject demo) {
        var result = new JsonObject();
        for (String key : List.of("name", "subject", "kind")) {
            result.add(key, demo.get(key));
        }
        result.addProperty("segments", demo.getAsJsonArray("frames").size());
        return result;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        var entries = new NbtList();
        for (var demo : demos.values()) {
            var entry = new NbtCompound();
            entry.putString("Header", header(demo).toString());
            entry.put("Frames", writeEntries(demo.getAsJsonArray("frames")));
            if (demo.has("inventory_changes")) {
                entry.put("InventoryChanges", writeEntries(demo.getAsJsonArray("inventory_changes")));
            }
            entries.add(entry);
        }
        nbt.put("Demonstrations", entries);
        return nbt;
    }

    private static NbtList writeEntries(JsonArray values) {
        var entries = new NbtList();
        values.forEach(value -> entries.add(NbtString.of(value.toString())));
        return entries;
    }

    private static JsonArray readEntries(NbtCompound entry, String key) {
        var result = new JsonArray();
        var stored = entry.getList(key, NbtElement.STRING_TYPE);
        for (int index = 0; index < stored.size(); index++) {
            result.add(JsonParser.parseString(stored.getString(index)));
        }
        return result;
    }

    private static AgentDemonstrations readNbt(NbtCompound nbt) {
        var state = new AgentDemonstrations();
        var entries = nbt.getList("Demonstrations", NbtElement.COMPOUND_TYPE);
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.getCompound(index);
            var demo = JsonParser.parseString(entry.getString("Header")).getAsJsonObject();
            demo.add("frames", readEntries(entry, "Frames"));
            if (entry.contains("InventoryChanges", NbtElement.LIST_TYPE)) {
                demo.add("inventory_changes", readEntries(entry, "InventoryChanges"));
            }
            state.demos.put(demo.get("name").getAsString(), demo);
        }
        return state;
    }
}
