package org.minecraftagents.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.entity.mob.HostileEntity;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.minecraftagents.tools.AgentTools.boundedText;
import static org.minecraftagents.tools.AgentTools.require;

/** Shared, versioned programs composed only of existing actor tools. */
public final class AgentSkills extends PersistentState {
    private static final int MAX_SKILLS = 64;
    private static final int MAX_VERSIONS = 16;
    private static final int MAX_STEPS = 8;
    private static final int MAX_CALLS = 12;
    private static final int MAX_RUN_TICKS = 600;
    private static final int MAX_RESULT_LENGTH = 1200;
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSkills.class);
    private static final Map<ServerWorld, Map<UUID, Run>> RUNS = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, List<JsonObject>> library = new LinkedHashMap<>();

    private record Step(String tool, JsonObject args, int repeat, JsonObject condition, JsonObject assertion) {
    }

    private record Program(List<Step> steps, Map<String, String> parameters) {
    }

    private static AgentSkills get(ServerWorld world) {
        return world.getServer().getOverworld().getPersistentStateManager().getOrCreate(
                AgentSkills::readNbt, AgentSkills::new, "minecraft_agents_skills");
    }

    static AgentTools.Result save(ServerWorld world, VillagerEntity actor, JsonObject args) {
        String name = boundedText(args, "name", 32, true);
        require(name.matches("[a-z][a-z0-9_]{0,31}"), "Use a short snake_case skill name.");
        String description = boundedText(args, "description", 120, true);
        JsonArray steps = args.getAsJsonArray("steps");
        require(steps.toString().length() <= 4000, "Keep the program under 4,000 characters.");
        Program program = compile(steps);
        AgentSkills state = get(world);
        List<JsonObject> versions = state.library.get(name);
        if (versions == null) {
            require(state.library.size() < MAX_SKILLS, "The skill library is full.");
            versions = new ArrayList<>();
            state.library.put(name, versions);
        } else {
            require(versions.get(0).get("author_id").getAsString().equals(actor.getUuidAsString()),
                    "Only the author can revise this skill. Save a new name to fork it.");
        }
        require(versions.size() < MAX_VERSIONS, "This skill has reached its version limit.");
        var skill = new JsonObject();
        skill.addProperty("name", name);
        skill.addProperty("description", description);
        skill.addProperty("author", actor.getName().getString());
        skill.addProperty("author_id", actor.getUuidAsString());
        skill.addProperty("version", versions.size() + 1);
        skill.add("parameters", GSON.toJsonTree(program.parameters()));
        skill.add("steps", steps.deepCopy());
        versions.add(skill);
        state.markDirty();
        LOGGER.info("[Agent skills] {} saved {} v{}.", actor.getName().getString(), name, versions.size());
        return AgentTools.ok("Saved " + name + " v" + versions.size() + ".", summary(skill));
    }

    static AgentTools.Result find(ServerWorld world, JsonObject args) {
        if (args.has("name")) {
            var skill = get(world).resolve(args).deepCopy();
            skill.remove("author_id");
            return AgentTools.ok("Skill program.", skill);
        }
        require(!args.has("version"), "Supply a skill name to read a specific version.");
        String query = boundedText(args, "query", 80, false);
        return AgentTools.ok("Shared skills.", get(world).search(query, 5));
    }

    static JsonArray preview(ServerWorld world) {
        return get(world).search("", 3);
    }

    private JsonArray search(String query, int limit) {
        var skills = new ArrayList<>(library.values());
        var found = new JsonArray();
        String search = query.toLowerCase(Locale.ROOT);
        for (int i = skills.size() - 1; i >= 0 && found.size() < limit; i--) {
            List<JsonObject> versions = skills.get(i);
            var latest = versions.get(versions.size() - 1);
            if (latest.get("name").getAsString().contains(search)
                    || latest.get("description").getAsString().toLowerCase(Locale.ROOT).contains(search)) {
                found.add(summary(latest));
            }
        }
        return found;
    }

    private static JsonObject summary(JsonObject skill) {
        var summary = skill.deepCopy();
        summary.remove("steps");
        summary.remove("author_id");
        return summary;
    }

    private JsonObject resolve(JsonObject args) {
        String name = boundedText(args, "name", 32, true);
        var versions = library.get(name);
        require(versions != null, "No saved skill named " + name + ".");
        int version = args.has("version") ? AgentTools.number(args, "version") : versions.size();
        require(version >= 1 && version <= versions.size(), "That skill version is unavailable.");
        return versions.get(version - 1);
    }

    static AgentTools.Result start(ServerWorld world, VillagerEntity actor, JsonObject args) {
        UUID id = actor.getUuid();
        require(!isActive(world, id) && !AgentTools.busy(world, id), "Wait for the current action to finish.");
        JsonObject skill = get(world).resolve(args);
        var program = compile(skill.getAsJsonArray("steps"));
        var values = args.has("args") ? args.getAsJsonObject("args") : new JsonObject();
        require(values.toString().length() <= 1000, "Keep the skill arguments under 1,000 characters.");
        require(values.keySet().equals(program.parameters().keySet()), "Supply these skill arguments " + program.parameters() + ".");
        var steps = new ArrayList<Step>();
        for (var step : program.steps()) {
            var bound = bind(step.args(), values);
            AgentTools.validate(AgentTools.primitive(step.tool()), bound);
            steps.add(new Step(step.tool(), bound, step.repeat(), step.condition(), step.assertion()));
        }
        var run = new Run(skill, steps, actor);
        RUNS.computeIfAbsent(world, key -> new HashMap<>()).put(id, run);
        return run.result();
    }

    private static Program compile(JsonArray steps) {
        require(!steps.isEmpty() && steps.size() <= MAX_STEPS, "Supply 1 to 8 skill steps.");
        var compiled = new ArrayList<Step>();
        var parameters = new LinkedHashMap<String, String>();
        int calls = 0;
        for (JsonElement raw : steps) {
            require(raw.isJsonObject(), "Each skill step must be a JSON object.");
            var step = raw.getAsJsonObject();
            require(Set.of("tool", "args", "repeat", "if", "assert").containsAll(step.keySet()),
                    "Skill steps accept tool, args, repeat, if and assert.");
            require(step.has("tool") && primitiveString(step.get("tool")), "Supply a tool name in each step.");
            String tool = boundedText(step, "tool", 32, true);
            var descriptor = AgentTools.primitive(tool);
            require(!step.has("args") || step.get("args").isJsonObject(), "Step args must be an object.");
            var args = step.has("args") ? step.getAsJsonObject("args") : new JsonObject();
            var sample = args.deepCopy();
            for (var parameter : descriptor.parameters()) {
                JsonElement value = args.get(parameter.name());
                if (primitiveString(value) && value.getAsString().startsWith("$")) {
                    String variable = value.getAsString().substring(1);
                    require(variable.matches("[a-z][a-z0-9_]{0,23}"), "Use a $snake_case parameter name.");
                    String type = parameter.type();
                    String earlier = parameters.putIfAbsent(variable, type);
                    require(earlier == null || earlier.equals(type), "Use one type for parameter " + variable + ".");
                    if (type.equals("integer")) {
                        sample.addProperty(parameter.name(), 0);
                    } else {
                        sample.addProperty(parameter.name(), "sample");
                    }
                }
            }
            AgentTools.validate(descriptor, sample);
            int repeat = 1;
            if (step.has("repeat")) {
                var value = step.get("repeat");
                require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Step repeat must be an integer.");
                repeat = AgentTools.number(step, "repeat");
                require(repeat >= 1 && repeat <= 4, "Use 1 to 4 repeats per step.");
            }
            calls += repeat;
            require(calls <= MAX_CALLS, "A skill can dispatch at most 12 calls.");
            var condition = predicate(step, "if");
            require(condition == null || !compiled.isEmpty(), "An if check needs a preceding step.");
            compiled.add(new Step(tool, args, repeat, condition, predicate(step, "assert")));
        }
        return new Program(compiled, parameters);
    }

    private static boolean primitiveString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static JsonObject predicate(JsonObject step, String field) {
        if (!step.has(field)) {
            return null;
        }
        require(step.get(field).isJsonObject(), "Use {path,equals} for " + field + ".");
        var test = step.getAsJsonObject(field);
        require(test.keySet().equals(Set.of("path", "equals")) && primitiveString(test.get("path"))
                        && test.get("equals").isJsonPrimitive(),
                "Use a result path and scalar equals value for " + field + ".");
        String path = test.get("path").getAsString();
        require(path.length() <= 64 && path.matches("(ok|message|data(\\.[a-zA-Z0-9_]+)*)"),
                "Check ok, message, or a field like data.block.");
        return test;
    }

    private static JsonObject bind(JsonObject template, JsonObject values) {
        var bound = new JsonObject();
        for (var entry : template.entrySet()) {
            var value = entry.getValue();
            if (primitiveString(value) && value.getAsString().startsWith("$")) {
                value = values.get(value.getAsString().substring(1));
            }
            bound.add(entry.getKey(), value);
        }
        return bound;
    }

    private static boolean matches(JsonObject predicate, JsonObject result) {
        JsonElement value = result;
        for (String key : predicate.get("path").getAsString().split("\\.")) {
            if (value == null || !value.isJsonObject()) {
                return false;
            }
            value = value.getAsJsonObject().get(key);
        }
        return predicate.get("equals").equals(value);
    }

    private static Run run(ServerWorld world, UUID id) {
        return RUNS.getOrDefault(world, Map.of()).get(id);
    }

    static boolean isActive(ServerWorld world, UUID id) {
        Run run = run(world, id);
        return run != null && run.active;
    }

    static AgentTools.Result status(ServerWorld world, UUID id) {
        Run run = run(world, id);
        return run == null ? AgentTools.error("No skill has run for this actor in this session.") : run.result();
    }

    static void cancel(ServerWorld world, UUID id, String reason) {
        Run run = run(world, id);
        if (run != null && run.active) {
            if (run.waiting) {
                AgentTools.cancelAction(world, id);
                run.last = GSON.toJsonTree(AgentTools.completed(world, id, run.steps.get(run.step).tool())).getAsJsonObject();
            }
            run.finish(false, reason);
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
            } else if (actor.getHealth() < run.health || underThreat(world, actor)) {
                cancel(world, id, "Interrupted by nearby danger.");
            } else if (run.ticksLeft-- == 0) {
                cancel(world, id, "Skill time budget reached.");
            } else {
                run.tick(world, actor);
            }
        }
    }

    private static boolean underThreat(ServerWorld world, VillagerEntity actor) {
        return !world.getOtherEntities(actor, actor.getBoundingBox().expand(4, 2, 4),
                entity -> entity instanceof HostileEntity && entity.isAlive()
                        && actor.squaredDistanceTo(entity) <= 16 && actor.canSee(entity)).isEmpty();
    }

    public static void clear(ServerWorld world) {
        RUNS.remove(world);
    }

    private static final class Run {
        private final String name;
        private final int version;
        private final String author;
        private final String runner;
        private final List<Step> steps;
        private final float health;
        private int ticksLeft = MAX_RUN_TICKS;
        private boolean active = true;
        private boolean ok = true;
        private boolean waiting;
        private int step;
        private int repeats;
        private int calls;
        private JsonObject last;
        private String message;

        private Run(JsonObject skill, List<Step> steps, VillagerEntity actor) {
            name = skill.get("name").getAsString();
            version = skill.get("version").getAsInt();
            author = skill.get("author").getAsString();
            runner = actor.getName().getString();
            this.steps = steps;
            health = actor.getHealth();
            message = "Running " + name + " v" + version + ".";
            LOGGER.info("[Agent skills] {} started {} v{} by {}.", runner, name, version, author);
        }

        private void tick(ServerWorld world, VillagerEntity actor) {
            Step current = steps.get(step);
            UUID id = actor.getUuid();
            if (waiting) {
                if (!AgentTools.busy(world, id)) {
                    accept(current, AgentTools.completed(world, id, current.tool()));
                }
                return;
            }
            if (repeats == 0 && current.condition() != null && !matches(current.condition(), last)) {
                advance();
                return;
            }
            calls++;
            var result = AgentTools.callStep(world, id, current.tool(), current.args());
            if (AgentTools.pending(current.tool(), result)) {
                waiting = true;
            } else {
                accept(current, result);
            }
        }

        private void accept(Step current, AgentTools.Result result) {
            waiting = false;
            last = GSON.toJsonTree(result).getAsJsonObject();
            if (!result.ok()) {
                finish(false, "Step " + (step + 1) + " " + current.tool() + " failed. " + result.message());
            } else if (current.assertion() != null && !matches(current.assertion(), last)) {
                finish(false, "Step " + (step + 1) + " " + current.tool() + " failed its result check.");
            } else if (++repeats == current.repeat()) {
                advance();
            }
        }

        private void advance() {
            repeats = 0;
            if (++step == steps.size()) {
                finish(true, "Completed " + name + " v" + version + " with " + calls + " tool calls.");
            }
        }

        private void finish(boolean success, String detail) {
            active = false;
            ok = success;
            message = detail;
            LOGGER.info("[Agent skills] {} {} v{} done ({} calls, ok={}). {}", runner, name, version, calls, ok, detail);
        }

        private AgentTools.Result result() {
            var data = new JsonObject();
            data.addProperty("name", name);
            data.addProperty("version", version);
            data.addProperty("author", author);
            data.addProperty("active", active);
            data.addProperty("calls", calls);
            if (last != null) {
                JsonObject summary = last;
                if (last.toString().length() > MAX_RESULT_LENGTH) {
                    summary = new JsonObject();
                    summary.addProperty("omitted", "Result exceeded " + MAX_RESULT_LENGTH + " characters.");
                }
                data.add("last", summary);
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
