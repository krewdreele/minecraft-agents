package org.minecraftagents.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** Same tool calls as the Java API, exposed for development in an operator's game chat. */
public final class AgentCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentCommands.class);

    private AgentCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var agent = literal("agent").requires(source -> source.hasPermissionLevel(2))
                .then(literal("tools").executes(context -> describe(context.getSource())))
                .then(literal("spawn").then(argument("name", StringArgumentType.word())
                        .then(argument("pos", BlockPosArgumentType.blockPos())
                                .executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "name"),
                                        BlockPosArgumentType.getLoadedBlockPos(context, "pos"))))))
                .then(literal("call").then(argument("actor", StringArgumentType.word())
                        .then(argument("tool", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    AgentTools.descriptors().stream().map(AgentTools.Descriptor::name)
                                            .filter(name -> name.startsWith(builder.getRemaining())).forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(context -> call(context.getSource(), StringArgumentType.getString(context, "actor"),
                                        StringArgumentType.getString(context, "tool"), "{}"))
                                .then(argument("json", StringArgumentType.greedyString())
                                        .executes(context -> call(context.getSource(), StringArgumentType.getString(context, "actor"),
                                                StringArgumentType.getString(context, "tool"), StringArgumentType.getString(context, "json")))))));

        agent.then(runCommand("run", AgentModelClient.Provider.OPENAI));
        agent.then(runCommand("run-local", AgentModelClient.Provider.OLLAMA));
        agent.then(literal("trial")
                .then(literal("status").executes(context -> {
                    var source = context.getSource();
                    source.sendFeedback(() -> Text.literal(AgentTrial.status(source.getWorld())), false);
                    return 1;
                }))
                .then(literal("stop").executes(context -> {
                    var source = context.getSource();
                    try {
                        AgentTrial.stop(source.getWorld());
                        return 1;
                    } catch (IllegalStateException failure) {
                        source.sendError(Text.literal(failure.getMessage()));
                        return 0;
                    }
                }))
                .then(literal("pair")
                        .then(argument("first", StringArgumentType.word())
                                .then(argument("second", StringArgumentType.word())
                                        .executes(context -> startTrial(context, AgentTrial.DEFAULT_DAYS, false))
                                        .then(argument("days", IntegerArgumentType.integer(1))
                                                .executes(context -> startTrial(context,
                                                        IntegerArgumentType.getInteger(context, "days"), false))))))
                .then(argument("first", StringArgumentType.word())
                        .then(argument("second", StringArgumentType.word())
                                .then(argument("third", StringArgumentType.word())
                                        .executes(context -> startTrial(context, AgentTrial.DEFAULT_DAYS, true))
                                        .then(argument("days", IntegerArgumentType.integer(1))
                                                .executes(context -> startTrial(context,
                                                        IntegerArgumentType.getInteger(context, "days"), true)))))));
        agent.then(literal("status").then(argument("actor", StringArgumentType.word())
                .executes(context -> loopStatus(context.getSource(), StringArgumentType.getString(context, "actor"), false))));
        agent.then(literal("stop").then(argument("actor", StringArgumentType.word())
                .executes(context -> loopStatus(context.getSource(), StringArgumentType.getString(context, "actor"), true))));
        dispatcher.register(agent);
        dispatcher.register(literal("agent-map").requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                .executes(context -> showMap(context.getSource(), AgentNavigation.MAP_RADIUS))
                .then(argument("radius", IntegerArgumentType.integer(1, AgentNavigation.MAX_RADIUS))
                        .executes(context -> showMap(context.getSource(), IntegerArgumentType.getInteger(context, "radius")))));
    }

    private static int showMap(ServerCommandSource source, int radius) {
        var map = AgentTools.mapSnapshot(source.getWorld(), source.getEntity(), radius);
        source.sendFeedback(() -> Text.literal(formatMap(map)), false);
        return 1;
    }

    private static int startTrial(CommandContext<ServerCommandSource> context, int days, boolean trio) {
        ServerCommandSource source = context.getSource();
        var names = new ArrayList<>(List.of(StringArgumentType.getString(context, "first"),
                StringArgumentType.getString(context, "second")));
        if (trio) {
            names.add(StringArgumentType.getString(context, "third"));
        }
        List<UUID> ids = names.stream().map(name -> findActor(source, name)).toList();
        if (ids.stream().anyMatch(id -> id == null)) {
            source.sendError(Text.literal("Choose unique actors within 32 blocks, or use their UUIDs."));
            return 0;
        }
        try {
            String message = AgentTrial.start(source.getWorld(), ids, days);
            source.sendFeedback(() -> Text.literal(message), false);
            return 1;
        } catch (IllegalStateException failure) {
            source.sendError(Text.literal(failure.getMessage()));
            return 0;
        }
    }

    private static LiteralArgumentBuilder<ServerCommandSource> runCommand(String name, AgentModelClient.Provider provider) {
        return literal(name).then(argument("actor", StringArgumentType.word())
                .then(argument("turns", IntegerArgumentType.integer(1, AgentLoop.MAX_TURNS))
                        .then(argument("goal", StringArgumentType.greedyString())
                                .executes(context -> startLoop(context.getSource(),
                                        StringArgumentType.getString(context, "actor"),
                                        IntegerArgumentType.getInteger(context, "turns"),
                                        StringArgumentType.getString(context, "goal"), provider)))));
    }

    private static int startLoop(ServerCommandSource source, String label, int turns, String goal,
            AgentModelClient.Provider provider) {
        UUID id = findActor(source, label);
        if (id == null) {
            source.sendError(Text.literal("Choose a unique actor within 32 blocks, or use its UUID."));
            return 0;
        }
        try {
            String message = AgentLoop.start(source.getWorld(), id, turns, goal, provider);
            source.sendFeedback(() -> Text.literal("[Agent loop] " + message), false);
            return 1;
        } catch (IllegalStateException failure) {
            source.sendError(Text.literal("[Agent loop] " + failure.getMessage()));
            return 0;
        }
    }

    private static int loopStatus(ServerCommandSource source, String label, boolean stop) {
        UUID id = findActor(source, label);
        if (id == null) {
            source.sendError(Text.literal("Choose a unique actor within 32 blocks, or use its UUID."));
            return 0;
        }
        String message = AgentLoop.status(source.getWorld(), id, stop);
        source.sendFeedback(() -> Text.literal("[Agent loop] " + label + " · " + message), false);
        return 1;
    }

    private static int describe(ServerCommandSource source) {
        for (AgentTools.Descriptor tool : AgentTools.descriptors()) {
            source.sendFeedback(() -> Text.literal(tool.name() + " — " + tool.description()), false);
        }
        return 1;
    }

    private static int spawn(ServerCommandSource source, String name, BlockPos pos) {
        var world = source.getWorld();
        if (!world.getWorldBorder().contains(pos) || name.length() > 24) {
            source.sendError(Text.literal("Use a name of at most 24 characters and a position inside the world border."));
            return 0;
        }
        VillagerEntity actor = EntityType.VILLAGER.create(world);
        if (actor == null) {
            source.sendError(Text.literal("Could not create the actor."));
            return 0;
        }
        actor.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        actor.setCustomName(Text.literal(name));
        actor.setCustomNameVisible(true);
        actor.setAiDisabled(true);
        actor.setPersistent();
        actor.addCommandTag(AgentTools.ACTOR_TAG);
        if (!world.isSpaceEmpty(actor) || !world.spawnEntity(actor)) {
            source.sendError(Text.literal("The actor could not spawn at that position."));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("Spawned " + name + " (" + actor.getUuidAsString() + ")."), false);
        return 1;
    }

    private static int call(ServerCommandSource source, String label, String tool, String json) {
        UUID id = findActor(source, label);
        if (id == null) {
            source.sendError(Text.literal("Choose a unique actor within 32 blocks, or use its UUID."));
            return 0;
        }
        JsonObject args;
        try {
            var value = JsonParser.parseString(json);
            if (!value.isJsonObject()) {
                source.sendError(Text.literal("Tool arguments must be a JSON object."));
                return 0;
            }
            args = value.getAsJsonObject();
        } catch (JsonParseException exception) {
            source.sendError(Text.literal("Invalid tool argument JSON."));
            return 0;
        }
        AgentTools.Result result = AgentTools.call(source.getWorld(), id, tool, args);
        Text feedback = Text.literal("[Agent " + tool + "] " + summarize(tool, result));
        if (!result.ok()) {
            source.sendError(feedback);
            return 0;
        }
        source.sendFeedback(() -> feedback, false);
        return 1;
    }

    private static String summarize(String tool, AgentTools.Result result) {
        if (result.data().isJsonNull()) {
            return result.message();
        }
        if (tool.equals("find_skills") || tool.equals("skill_status")) {
            LOGGER.info("[Agent {}] {}", tool, result.data());
            return result.message() + " Details in the run log.";
        }
        if (tool.equals("look")) {
            var view = result.data().getAsJsonObject();
            var entities = view.getAsJsonArray("entities");
            var nearby = new ArrayList<String>();
            for (int index = 0; index < Math.min(4, entities.size()); index++) {
                var entity = entities.get(index).getAsJsonObject();
                nearby.add(entity.get("name").getAsString() + " #" + entity.get("id").getAsInt());
            }
            LOGGER.info("[Agent look] {}", result.data());
            String preview = nearby.isEmpty() ? "" : " " + String.join(", ", nearby) + ".";
            return view.getAsJsonArray("blocks").size() + " visible blocks, " + entities.size() + " nearby entities."
                    + preview + attackSummary(view) + routeSummary(view) + " Details in the run log.";
        }
        if (tool.equals("map")) {
            var map = result.data().getAsJsonObject();
            LOGGER.info("[Agent map] {}", map);
            return formatMap(map);
        }
        if (tool.equals("read_knowledge")) {
            var notes = new ArrayList<String>();
            for (var value : result.data().getAsJsonArray()) {
                var note = value.getAsJsonObject();
                notes.add(note.get("author").getAsString() + " · " + note.get("topic").getAsString()
                        + " · " + note.get("note").getAsString());
            }
            LOGGER.info("[Agent knowledge] {}", result.data());
            return notes.isEmpty() ? result.message() : String.join("\n", notes);
        }
        if (tool.equals("recall")) {
            var page = result.data().getAsJsonObject();
            LOGGER.info("[Agent memory] {}", page);
            var lines = new ArrayList<String>();
            lines.add(result.message());
            for (var value : page.getAsJsonArray("entries")) {
                var entry = value.getAsJsonObject();
                String label = entry.has("partner") ? entry.get("direction").getAsString() + " "
                        + entry.get("partner").getAsString() : entry.get("action").getAsString();
                lines.add(label + " · " + entry.get("detail").getAsString());
            }
            if (page.has("nextOffset")) {
                lines.add("Next offset " + page.get("nextOffset").getAsInt() + ".");
            }
            return String.join("\n", lines);
        }
        if (tool.equals("recipe_book")) {
            var page = result.data().getAsJsonObject();
            var lines = new ArrayList<String>();
            lines.add(result.message());
            for (var value : page.getAsJsonArray("recipes")) {
                var recipe = value.getAsJsonObject();
                String item = recipe.get("item").getAsString();
                if (recipe.has("ingredients")) {
                    item += " x" + recipe.get("yield").getAsInt() + " from " + recipe.get("ingredients").getAsString()
                            + (recipe.get("table").getAsBoolean() ? " (table)" : "");
                }
                lines.add(item);
            }
            if (page.has("nextOffset")) {
                lines.add("Next offset " + page.get("nextOffset").getAsInt() + ".");
            }
            LOGGER.info("[Agent recipe book] {}", page);
            return String.join("\n", lines);
        }
        String json = result.data().toString();
        if (json.length() > 300) {
            LOGGER.info("[Agent {}] {}", tool, json);
            return result.message() + " Details in the run log.";
        }
        return result.message() + " " + json;
    }

    private static String formatMap(JsonObject map) {
        int minX = map.get("minX").getAsInt();
        int minZ = map.get("minZ").getAsInt();
        var rows = new ArrayList<StringBuilder>();
        map.getAsJsonArray("rows").forEach(row -> rows.add(new StringBuilder(row.getAsString())));
        var labels = new ArrayList<String>();
        for (var value : map.getAsJsonArray("landmarks")) {
            var landmark = value.getAsJsonObject();
            var position = landmark.getAsJsonObject("position");
            int x = position.get("x").getAsInt();
            int y = position.get("y").getAsInt();
            int z = position.get("z").getAsInt();
            char symbol = switch (landmark.get("kind").getAsString()) {
                case "sign" -> 'S';
                case "player" -> 'P';
                case "actor" -> 'A';
                default -> 'C';
            };
            mark(rows.get(z - minZ), x - minX, symbol);
            labels.add(symbol + " " + landmark.get("label").getAsString() + " (" + x + ", " + y + ", " + z + ")");
        }
        for (var value : map.getAsJsonArray("mobs")) {
            var mob = value.getAsJsonObject();
            var position = mob.getAsJsonObject("position");
            int x = position.get("x").getAsInt();
            int y = position.get("y").getAsInt();
            int z = position.get("z").getAsInt();
            char symbol = mob.get("hostile").getAsBoolean() ? '!' : 'M';
            mark(rows.get(z - minZ), x - minX, symbol);
            labels.add(symbol + " " + mob.get("type").getAsString().replace("minecraft:", "")
                    + " #" + mob.get("entityId").getAsInt() + " (" + x + ", " + y + ", " + z + ")");
        }
        String names = labels.isEmpty() ? "" : "\n" + String.join("\n", labels);
        return "Northwest x=" + minX + ", z=" + minZ + ", feet y=" + map.get("feetY").getAsInt()
                + "\n" + String.join("\n", rows)
                + "\n" + AgentNavigation.LEGEND + ". S sign, P player, A actor, C chest, ! hostile, M animal." + names;
    }

    private static void mark(StringBuilder row, int column, char symbol) {
        char current = row.charAt(column);
        if (current != '@' && current != 'L' && (symbol == '!' || "#.^v?".indexOf(current) >= 0)) {
            row.setCharAt(column, symbol);
        }
    }

    private static String attackSummary(JsonObject view) {
        var attack = view.getAsJsonObject("attack");
        if (attack == null) {
            return "";
        }
        return " " + attack.get("message").getAsString()
                + " Hits: " + attack.get("hits").getAsInt() + "/" + attack.get("requestedSwings").getAsInt() + ".";
    }

    private static String routeSummary(JsonObject view) {
        var route = view.getAsJsonObject("route");
        if (route == null) {
            return "";
        }
        return " " + route.get("message").getAsString() + " Steps: " + route.get("steps").getAsInt()
                + "/" + route.get("totalSteps").getAsInt() + ".";
    }

    private static UUID findActor(ServerCommandSource source, String label) {
        try {
            return UUID.fromString(label);
        } catch (IllegalArgumentException ignored) {
            List<VillagerEntity> matches = source.getWorld().getEntitiesByClass(VillagerEntity.class,
                    new Box(source.getPosition(), source.getPosition()).expand(32),
                    actor -> AgentTools.isActor(actor) && actor.squaredDistanceTo(source.getPosition()) <= 32 * 32
                            && actor.getName().getString().equalsIgnoreCase(label));
            return matches.size() == 1 ? matches.get(0).getUuid() : null;
        }
    }
}
