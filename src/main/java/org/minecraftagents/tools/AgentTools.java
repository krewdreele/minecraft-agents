package org.minecraftagents.tools;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** The model tools and the keyboard and mouse primitives available to saved programs. */
final class AgentTools {
    static final String ACTOR_TAG = "minecraft_agents_actor";
    private static final Gson GSON = new Gson();

    record Parameter(String name, String type, String description, boolean required) {
    }

    record Descriptor(String name, String description, List<Parameter> parameters) {
    }

    record Result(boolean ok, String message, JsonElement data) {
    }

    private record Tool(Descriptor descriptor, BiFunction<Context, JsonObject, Result> action) {
    }

    private record Context(ServerWorld world, VillagerEntity actor) {
    }

    private static final List<Tool> TOOLS = List.of(
            tool("press_key", "Hold movement keys or select a carried slot.",
                    (ctx, args) -> AgentInput.press(ctx.world(), ctx.actor(), args),
                    string("key", "w, a, s, d, space, a combination like w+space, none to wait, or 1–9."),
                    optional("ticks", "integer", "Hold for 1 to 160 ticks. Default 5. W moves about 0.2 blocks per tick.")),
            tool("mouse", "Turn the camera or press a mouse button.",
                    (ctx, args) -> AgentInput.mouse(ctx.world(), ctx.actor(), args),
                    optional("yaw", "integer", "Relative horizontal degrees. Negative turns left."),
                    optional("pitch", "integer", "Relative vertical degrees. Positive looks down."),
                    optional("pitch_to", "integer", "Target vertical angle from -90 to 90. Use 0 to level the view from any pitch. Replaces pitch."),
                    optional("button", "string", "left to mine at the crosshair; right to place a selected block."),
                    optional("ticks", "integer", "Left button hold. 1 to 160 ticks; about 60 for one log by hand.")),
            tool("save_skill", "Publish or revise a complete input program that achieves its purpose from a fresh start.",
                    (ctx, args) -> AgentSkills.save(ctx.world(), ctx.actor(), args),
                    string("name", "Short snake_case name."), string("description", "What the program does."),
                    new Parameter("program", "array", "Complete array of instruction objects. Preserve the required stages when adding a recovery branch.", true),
                    optional("demo", "string", "Name of the recorded demonstration this program learned from.")),
            tool("find_skills", "Browse shared input programs or read a particular version.",
                    (ctx, args) -> AgentSkills.find(ctx.world(), ctx.actor(), args),
                    optional("query", "string", "Keyword in names or descriptions."),
                    optional("name", "string", "Exact skill name."),
                    optional("version", "integer", "Version to read. Default latest.")),
            tool("run_skill", "Run a saved input program.",
                    (ctx, args) -> AgentSkills.start(ctx.world(), ctx.actor(), args),
                    string("name", "Saved skill name."),
                    optional("version", "integer", "Version to run. Default latest.")),
            tool("skill_status", "Read the outcome of your current or last skill run.",
                    (ctx, args) -> AgentSkills.status(ctx.world(), ctx.actor().getUuid())),
            tool("read_demo", "Browse demonstrations or read one complete input sequence with its observed results.",
                    (ctx, args) -> AgentDemonstrations.read(ctx.world(), ctx.actor(), args),
                    optional("name", "string", "Exact demonstration name. Omit to browse.")));

    private AgentTools() {
    }

    static List<Descriptor> descriptors() {
        return TOOLS.stream().map(Tool::descriptor).toList();
    }

    static Result call(ServerWorld world, VillagerEntity actor, String name, JsonObject args, boolean skillStep) {
        if (!world.getServer().isOnThread()) {
            throw new IllegalStateException("Call agent tools on the Minecraft server thread.");
        }
        if (!isActor(actor) || actor.getWorld() != world) {
            return error("The actor is unavailable in this world.");
        }
        return TOOLS.stream().filter(tool -> tool.descriptor().name().equals(name)).findFirst()
                .map(tool -> invoke(world, actor, tool, args, skillStep))
                .orElseGet(() -> error("Unknown tool " + name + "."));
    }

    private static Result invoke(ServerWorld world, VillagerEntity actor, Tool tool, JsonObject args, boolean skillStep) {
        try {
            if (!skillStep && isInputPrimitive(tool.descriptor().name())) {
                require(!AgentSkills.isActive(world, actor.getUuid()), "Wait for the skill to finish.");
            }
            validate(tool.descriptor(), args);
            return tool.action().apply(new Context(world, actor), args);
        } catch (ToolFailure failure) {
            return error(failure.getMessage());
        }
    }

    static boolean isInputPrimitive(String name) {
        return name.equals("press_key") || name.equals("mouse");
    }

    static Descriptor inputPrimitive(String name) {
        require(isInputPrimitive(name), "Use press_key or mouse. Invalid input " + name + ".");
        return descriptor(name);
    }

    static Descriptor descriptor(String name) {
        return TOOLS.stream().map(Tool::descriptor).filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    static boolean isActor(Entity entity) {
        return entity instanceof VillagerEntity && entity.isAlive() && entity.getCommandTags().contains(ACTOR_TAG);
    }

    static Predicate<BlockState> blockMatcher(String name) {
        boolean tagged = name.startsWith("#");
        Identifier id = Identifier.tryParse(tagged ? name.substring(1) : name);
        require(id != null, "Use a block ID or a block tag beginning with #.");
        if (tagged) {
            TagKey<Block> tag = TagKey.of(RegistryKeys.BLOCK, id);
            require(Registries.BLOCK.getEntryList(tag).isPresent(), "Use a known block tag.");
            return state -> state.isIn(tag);
        }
        require(Registries.BLOCK.containsId(id), "Use a known block ID.");
        Block block = Registries.BLOCK.get(id);
        return state -> state.isOf(block);
    }

    private static Tool tool(String name, String description, BiFunction<Context, JsonObject, Result> action,
            Parameter... parameters) {
        return new Tool(new Descriptor(name, description, List.of(parameters)), action);
    }

    private static Parameter string(String name, String description) {
        return new Parameter(name, "string", description, true);
    }

    private static Parameter optional(String name, String type, String description) {
        return new Parameter(name, type, description, false);
    }

    static void validate(Descriptor tool, JsonObject args) {
        require(args != null, "Supply a JSON argument object.");
        for (String name : args.keySet()) {
            require(tool.parameters().stream().anyMatch(parameter -> parameter.name().equals(name)),
                    "Unexpected argument " + name + ".");
        }
        for (Parameter parameter : tool.parameters()) {
            String name = parameter.name();
            if (!args.has(name)) {
                require(!parameter.required(), "Missing argument " + name + ".");
                continue;
            }
            JsonElement value = args.get(name);
            boolean isString = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            boolean valid = switch (parameter.type()) {
                case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
                case "string" -> isString;
                // Older skill submissions encoded their instruction array as text.
                case "array" -> value.isJsonArray() || (tool.name().equals("save_skill") && isString);
                default -> throw new IllegalStateException("Unknown parameter type " + parameter.type());
            };
            require(valid, "Use a JSON " + parameter.type() + " for " + name + ".");
            if (parameter.type().equals("integer")) {
                number(args, name);
            }
        }
    }

    static int number(JsonObject args, String name) {
        try {
            return args.get(name).getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new ToolFailure("Use a whole number in range for " + name + ".");
        }
    }

    private static String text(JsonObject args, String name) {
        return args.get(name).getAsString();
    }

    static String boundedText(JsonObject args, String name, int length, boolean required) {
        String value = args.has(name) ? text(args, name).strip() : "";
        require((!required || !value.isEmpty()) && value.length() <= length
                        && value.chars().noneMatch(Character::isISOControl),
                "Use " + (required ? "1 to " : "at most ") + length + " characters for " + name + ".");
        return value;
    }

    static Result ok(String message, Object data) {
        return new Result(true, message, GSON.toJsonTree(data));
    }

    static Result error(String message) {
        return new Result(false, message, JsonNull.INSTANCE);
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new ToolFailure(message);
        }
    }

    static final class ToolFailure extends RuntimeException {
        ToolFailure(String message) {
            super(message);
        }
    }
}
