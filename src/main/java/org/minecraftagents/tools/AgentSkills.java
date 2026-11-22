package org.minecraftagents.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.minecraftagents.tools.AgentTools.boundedText;
import static org.minecraftagents.tools.AgentTools.require;

/** A shared library of versioned keyboard and mouse programs. */
public final class AgentSkills extends PersistentState {
    private static final int MAX_SKILLS = 64;
    private static final int MAX_VERSIONS = 16;
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSkills.class);
    private static final Map<ServerWorld, Map<UUID, Run>> RUNS = new HashMap<>();
    private final Map<String, List<JsonObject>> library = new LinkedHashMap<>();

    record Ref(String name, int version) {
        static Ref from(JsonObject skill) {
            return new Ref(skill.get("name").getAsString(), skill.get("version").getAsInt());
        }

        JsonObject json() {
            var data = new JsonObject();
            data.addProperty("name", name);
            data.addProperty("version", version);
            return data;
        }

        @Override
        public String toString() {
            return name + " v" + version;
        }
    }

    record Attempt(JsonObject details, AgentProgram.Execution execution) {
    }

    private static AgentSkills get(ServerWorld world, VillagerEntity actor) {
        return world.getServer().getOverworld().getPersistentStateManager().getOrCreate(
                AgentSkills::readNbt, AgentSkills::new, AgentArena.libraryId(world, actor, "minecraft_agents_skills"));
    }

    static AgentTools.Result save(ServerWorld world, VillagerEntity actor, JsonObject args) {
        var state = get(world, actor);
        var publication = state.new Publication(actor);
        var skill = publication.stage(skillName(args), submission(world, actor, args));
        publication.commit();
        return AgentTools.ok("Saved " + Ref.from(skill) + ".", summary(skill));
    }

    private static JsonObject submission(ServerWorld world, VillagerEntity actor, JsonObject args) {
        var skill = new JsonObject();
        var program = args.get("program");
        skill.add("program", AgentProgram.parse(program));
        skill.addProperty("source", AgentProgram.source(program));
        skill.addProperty("description", boundedText(args, "description", 120, true));
        if (args.has("demo")) {
            String demo = boundedText(args, "demo", 32, true);
            require(AgentDemonstrations.exists(world, actor, demo), "Choose an existing demonstration.");
            skill.addProperty("demo", demo);
        }
        return skill;
    }

    private static String skillName(JsonObject args) {
        String name = boundedText(args, "name", 32, true);
        require(name.matches("[a-z][a-z0-9_]{0,31}"), "Use a short snake_case skill name.");
        return name;
    }

    static AgentTools.Result repair(ServerWorld world, VillagerEntity actor, JsonObject args,
                                   AgentProgram.CallPath target, JsonObject rootIntent) {
        AgentSkills state = get(world, actor);
        String name = skillName(args);
        require(name.equals(target.skill().name()) || !state.library.containsKey(name),
                "Revise " + target.skill().name() + " or choose a new name to fork it.");
        var previous = state.resolve(target.skill());
        var revision = submission(world, actor, args);
        require(!revision.get("program").equals(previous.get("program")), "Change the selected skill's program before retesting.");
        if (!revision.has("demo") && previous.has("demo")) {
            revision.add("demo", previous.get("demo"));
        }
        if (previous.has("intent")) {
            revision.add("intent", previous.get("intent").deepCopy());
        }
        var publication = state.new Publication(actor);
        var root = publication.rebuildPath(target, name, revision, rootIntent);
        publication.commit();
        var data = summary(root);
        if (target.parent() == null) {
            return AgentTools.ok("Saved " + Ref.from(root) + ".", data);
        }
        data.add("revised_sub_skill", summary(revision));
        return AgentTools.ok("Saved " + Ref.from(revision) + " and updated " + Ref.from(root) + " for retest.", data);
    }

    private final class Publication {
        private final VillagerEntity actor;
        private final Map<String, List<JsonObject>> staged = new LinkedHashMap<>();

        private Publication(VillagerEntity actor) {
            this.actor = actor;
        }

        private JsonObject stage(String name, JsonObject skill) {
            var existing = library.getOrDefault(name, List.of());
            var pending = staged.getOrDefault(name, List.of());
            if (existing.isEmpty() && pending.isEmpty()) {
                long newNames = staged.keySet().stream().filter(key -> !library.containsKey(key)).count();
                require(library.size() + newNames < MAX_SKILLS, "The skill library is full.");
            } else if (!existing.isEmpty()) {
                require(owns(existing.get(0), actor), "Only the author can revise this skill. Save a new name to fork it.");
            }
            int count = existing.size() + pending.size();
            require(count < MAX_VERSIONS, "This skill has reached its version limit. Save a new name to fork it.");
            skill.addProperty("name", name);
            skill.addProperty("author", actor.getName().getString());
            skill.addProperty("author_id", actor.getUuidAsString());
            skill.addProperty("version", count + 1);
            staged.computeIfAbsent(name, key -> new ArrayList<>()).add(skill);
            return skill;
        }

        private String parentName(JsonObject parent) {
            String name = Ref.from(parent).name();
            if (owns(parent, actor) && library.get(name).size() + staged.getOrDefault(name, List.of()).size() < MAX_VERSIONS) {
                return name;
            }
            for (int index = 1; ; index++) {
                String suffix = index == 1 ? "_adapted" : "_adapted_" + index;
                String fork = name.substring(0, Math.min(name.length(), 32 - suffix.length())) + suffix;
                if (!library.containsKey(fork) && !staged.containsKey(fork)) {
                    return fork;
                }
            }
        }

        private JsonObject rebuildPath(AgentProgram.CallPath path, String name, JsonObject revision, JsonObject rootIntent) {
            var caller = path.parent();
            if (caller == null && rootIntent != null) {
                revision.add("intent", rootIntent.deepCopy());
            }
            var saved = stage(name, revision);
            if (caller == null) {
                return saved;
            }
            var parent = resolve(caller.skill()).deepCopy();
            var program = parent.getAsJsonArray("program");
            path.replaceCall(program, Ref.from(saved));
            String source = program.toString();
            require(source.length() <= AgentProgram.MAX_SOURCE_LENGTH, "The updated parent program exceeds 6,000 characters.");
            parent.addProperty("source", source);
            return rebuildPath(caller, parentName(parent), parent, rootIntent);
        }

        private void commit() {
            staged.forEach((name, versions) -> {
                library.computeIfAbsent(name, key -> new ArrayList<>()).addAll(versions);
                versions.forEach(skill -> LOGGER.info("[Agent skills] {} saved {}.", actor.getName().getString(), Ref.from(skill)));
            });
            markDirty();
        }
    }

    static AgentTools.Result find(ServerWorld world, VillagerEntity actor, JsonObject args) {
        var state = get(world, actor);
        if (args.has("name")) {
            return AgentTools.ok("Skill program.", publicSkill(state.resolve(args)));
        }
        require(!args.has("version"), "Supply a skill name to read a specific version.");
        return AgentTools.ok("Shared skills.", state.search(boundedText(args, "query", 80, false), 5));
    }

    static void rememberIntent(ServerWorld world, VillagerEntity actor, JsonObject published, JsonObject intent) {
        var state = get(world, actor);
        state.resolve(published).add("intent", intent.deepCopy());
        state.markDirty();
    }

    static JsonObject adaptationTarget(ServerWorld world, VillagerEntity actor, JsonObject args) {
        var state = get(world, actor);
        return state.adaptationTarget(actor, state.resolve(args));
    }

    static JsonObject adaptationTarget(ServerWorld world, VillagerEntity actor, Ref ref) {
        var state = get(world, actor);
        return state.adaptationTarget(actor, state.resolve(ref));
    }

    private JsonObject adaptationTarget(VillagerEntity actor, JsonObject skill) {
        var visible = publicSkill(skill);
        visible.addProperty("can_revise", owns(skill, actor) && library.get(Ref.from(skill).name()).size() < MAX_VERSIONS);
        return visible;
    }

    private static boolean owns(JsonObject skill, VillagerEntity actor) {
        return skill.get("author_id").getAsString().equals(actor.getUuidAsString());
    }

    static JsonArray preview(ServerWorld world, VillagerEntity actor) {
        return get(world, actor).search("", 3);
    }

    static JsonArray list(ServerWorld world, VillagerEntity actor) {
        return get(world, actor).search("", MAX_SKILLS);
    }

    private JsonArray search(String query, int limit) {
        var skills = new ArrayList<>(library.values());
        var found = new JsonArray();
        String search = query.toLowerCase(Locale.ROOT);
        for (int i = skills.size() - 1; i >= 0 && found.size() < limit; i--) {
            var latest = latest(skills.get(i));
            if (latest != null && (latest.get("name").getAsString().contains(search)
                    || latest.get("description").getAsString().toLowerCase(Locale.ROOT).contains(search))) {
                found.add(summary(latest));
            }
        }
        return found;
    }

    private static JsonObject publicSkill(JsonObject skill) {
        var visible = skill.deepCopy();
        visible.remove("author_id");
        visible.remove("source");
        return visible;
    }

    private static JsonObject summary(JsonObject skill) {
        var summary = publicSkill(skill);
        summary.remove("program");
        summary.remove("intent");
        return summary;
    }

    private static JsonObject latest(List<JsonObject> versions) {
        // Keep existing version numbers when a save also contains old step-based programs.
        for (int index = versions.size() - 1; index >= 0; index--) {
            var version = versions.get(index);
            if (version.has("program")) {
                return version;
            }
        }
        return null;
    }

    private JsonObject resolve(JsonObject args) {
        int version = args.has("version") ? AgentTools.number(args, "version") : 0;
        require(!args.has("version") || version > 0, "Use a positive skill version.");
        return resolve(new Ref(boundedText(args, "name", 32, true), version));
    }

    private JsonObject resolve(Ref ref) {
        var versions = library.get(ref.name());
        require(versions != null, "No saved skill named " + ref.name() + ".");
        int version = ref.version();
        require(version <= versions.size(), "That skill version is unavailable.");
        var skill = version == 0 ? latest(versions) : versions.get(version - 1);
        require(skill != null && skill.has("program"), "That skill version has no keyboard and mouse program.");
        return skill;
    }

    static JsonArray inputProgram(ServerWorld world, VillagerEntity actor, Ref ref) {
        return get(world, actor).resolve(ref).getAsJsonArray("program");
    }

    static AgentTools.Result start(ServerWorld world, VillagerEntity actor, JsonObject args) {
        UUID id = actor.getUuid();
        require(!isActive(world, id) && !AgentInput.isActive(world, id), "Wait for the current action to finish.");
        var skill = get(world, actor).resolve(args);
        var debug = AgentDebug.state(world, id);
        var program = skill.getAsJsonArray("program");
        var run = new Run(skill, new AgentProgram.Execution(Ref.from(skill), program, actor, debug), actor);
        String source = skill.has("source") ? skill.get("source").getAsString() : program.toString();
        debug.started(world, actor, run.name + " v" + run.version, source);
        RUNS.computeIfAbsent(world, key -> new HashMap<>()).put(id, run);
        return run.result();
    }

    private static Run run(ServerWorld world, UUID id) {
        return RUNS.getOrDefault(world, Map.of()).get(id);
    }

    static boolean isActive(ServerWorld world, UUID id) {
        var run = run(world, id);
        return run != null && run.active;
    }

    static AgentTools.Result status(ServerWorld world, UUID id) {
        var run = run(world, id);
        return run == null ? AgentTools.error("No skill has run for this actor in this session.") : run.result();
    }

    static Attempt completed(ServerWorld world, VillagerEntity actor) {
        var run = run(world, actor.getUuid());
        if (run == null || run.active) {
            return null;
        }
        var data = adaptationTarget(world, actor, run.result().data().getAsJsonObject());
        data.addProperty("program_passed", run.ok);
        data.addProperty("result", run.message);
        data.add("evidence", run.program.evidence());
        if (!run.ok) {
            data.add("failed_call_path", run.program.repairTarget(null).lineage());
        }
        return new Attempt(data, run.program);
    }

    static void cancel(ServerWorld world, UUID id, String reason) {
        var run = run(world, id);
        if (run != null && run.active) {
            AgentInput.cancel(world, id);
            var actor = world.getEntity(id) instanceof VillagerEntity villager ? villager : null;
            run.finish(world, actor, false, reason);
        }
    }

    public static void tick(ServerWorld world) {
        for (var entry : RUNS.getOrDefault(world, Map.of()).entrySet()) {
            var id = entry.getKey();
            var run = entry.getValue();
            if (!run.active) {
                continue;
            }
            if (!(world.getEntity(id) instanceof VillagerEntity actor) || !AgentTools.isActor(actor)) {
                cancel(world, id, "Actor unavailable.");
            } else if (run.ticksLeft-- == 0) {
                cancel(world, id, "Skill time budget reached.");
            } else {
                run.tick(world, actor);
            }
        }
    }

    public static void clear(ServerWorld world) {
        RUNS.remove(world);
        AgentDebug.clear(world);
    }

    private static final class Run {
        private final String name;
        private final int version;
        private final String author;
        private final String runner;
        private final AgentProgram.Execution program;
        private int ticksLeft = AgentProgram.MAX_TICKS;
        private boolean active = true;
        private boolean ok = true;
        private String message;

        private Run(JsonObject skill, AgentProgram.Execution program, VillagerEntity actor) {
            name = skill.get("name").getAsString();
            version = skill.get("version").getAsInt();
            author = skill.get("author").getAsString();
            runner = actor.getName().getString();
            this.program = program;
            message = "Running " + name + " v" + version + ".";
            LOGGER.info("[Agent skills] {} started {} v{} by {}.", runner, name, version, author);
        }

        private void tick(ServerWorld world, VillagerEntity actor) {
            try {
                if (program.tick(world, actor)) {
                    finish(world, actor, true, "Completed " + name + " v" + version + " with " + program.calls() + " inputs.");
                }
            } catch (AgentTools.ToolFailure failure) {
                var path = program.repairTarget(null);
                String detail = path.parent() == null ? failure.getMessage() : path.skill() + " failed. " + failure.getMessage();
                finish(world, actor, false, detail);
            }
        }

        private void finish(ServerWorld world, VillagerEntity actor, boolean success, String detail) {
            active = false;
            ok = success;
            message = detail;
            program.finished(world, actor, success, detail);
            LOGGER.info("[Agent skills] {} {} v{} done ({} calls, ok={}). {}", runner, name, version, program.calls(), ok, detail);
        }

        private AgentTools.Result result() {
            var data = new JsonObject();
            data.addProperty("name", name);
            data.addProperty("version", version);
            data.addProperty("author", author);
            data.addProperty("active", active);
            data.addProperty("calls", program.calls());
            if (!active && !ok) {
                data.add("failed_call_path", program.repairTarget(null).lineage());
            }
            return new AgentTools.Result(ok, message, data);
        }
    }

    @Override
    public NbtCompound writeNbt(NbtCompound root) {
        var entries = new NbtList();
        library.values().forEach(versions -> versions.forEach(skill -> entries.add(NbtString.of(skill.toString()))));
        root.put("Skills", entries);
        return root;
    }

    private static AgentSkills readNbt(NbtCompound root) {
        var state = new AgentSkills();
        var entries = root.getList("Skills", NbtElement.STRING_TYPE);
        for (int i = 0; i < entries.size(); i++) {
            var skill = JsonParser.parseString(entries.getString(i)).getAsJsonObject();
            state.library.computeIfAbsent(skill.get("name").getAsString(), name -> new ArrayList<>()).add(skill);
        }
        return state;
    }
}
