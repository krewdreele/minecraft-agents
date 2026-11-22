package org.minecraftagents.tools;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.UUID;
import java.util.function.BiFunction;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** Operator commands for recording, learning, and testing skills. */
public final class AgentCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentCommands.class);

    private enum DebugAction { PLAN, RUN, TRACE, TRACE_ON, TRACE_OFF }

    private AgentCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var agent = literal("agent").requires(source -> source.hasPermissionLevel(2));
        agent.then(literal("arena").executes(context -> arena(context.getSource())));
        agent.then(runCommand("run", AgentModelClient.Provider.OPENAI, false));
        agent.then(runCommand("run-local", AgentModelClient.Provider.OLLAMA, false));
        agent.then(runCommand("adapt", AgentModelClient.Provider.OPENAI, true));
        agent.then(runCommand("adapt-local", AgentModelClient.Provider.OLLAMA, true));
        agent.then(literal("do").then(argument("actor", StringArgumentType.word())
                .then(argument("skill", StringArgumentType.word())
                        .executes(context -> skill(context, true, 0))
                        .then(argument("version", IntegerArgumentType.integer(1))
                                .executes(context -> skill(context, true, IntegerArgumentType.getInteger(context, "version")))))));
        agent.then(literal("skills").then(argument("actor", StringArgumentType.word())
                .executes(AgentCommands::listSkills)
                .then(argument("skill", StringArgumentType.word())
                        .executes(context -> skill(context, false, 0))
                        .then(argument("version", IntegerArgumentType.integer(1))
                                .executes(context -> skill(context, false, IntegerArgumentType.getInteger(context, "version")))))));
        agent.then(literal("status").then(argument("actor", StringArgumentType.word()).executes(AgentCommands::status)));
        agent.then(literal("stop").then(argument("actor", StringArgumentType.word()).executes(AgentCommands::stop)));
        agent.then(literal("view").then(argument("actor", StringArgumentType.word()).executes(AgentCommands::view)));
        agent.then(literal("debug").then(argument("actor", StringArgumentType.word())
                .then(literal("plan").executes(context -> debug(context, DebugAction.PLAN)))
                .then(literal("run").executes(context -> debug(context, DebugAction.RUN)))
                .then(literal("trace").executes(context -> debug(context, DebugAction.TRACE))
                        .then(literal("on").executes(context -> debug(context, DebugAction.TRACE_ON)))
                        .then(literal("off").executes(context -> debug(context, DebugAction.TRACE_OFF))))));
        agent.then(literal("demo")
                .then(literal("record").then(argument("name", StringArgumentType.word())
                        .executes(context -> demo(context, false, false))
                        .then(argument("subject", StringArgumentType.word())
                                .executes(context -> demo(context, false, true)))))
                .then(literal("stop")
                        .executes(context -> demo(context, true, false))
                        .then(argument("subject", StringArgumentType.word())
                                .executes(context -> demo(context, true, true)))));
        dispatcher.register(agent);
    }

    private static int arena(ServerCommandSource source) {
        try {
            return feedback(source, AgentArena.create(source));
        } catch (AgentTools.ToolFailure failure) {
            return error(source, failure.getMessage());
        }
    }

    private static LiteralArgumentBuilder<ServerCommandSource> runCommand(String name, AgentModelClient.Provider provider,
                                                                          boolean adapt) {
        String input = adapt ? "skill" : "goal";
        return literal(name).then(argument("actor", StringArgumentType.word())
                .then(argument("turns", IntegerArgumentType.integer(1, AgentLoop.MAX_TURNS))
                        .then(argument(input, adapt ? StringArgumentType.word() : StringArgumentType.greedyString())
                                .executes(context -> withActor(context, (source, actor) -> {
                                    String task = StringArgumentType.getString(context, input);
                                    int turns = IntegerArgumentType.getInteger(context, "turns");
                                    UUID operator = source.getEntity() instanceof ServerPlayerEntity player ? player.getUuid() : null;
                                    String message = adapt ? AgentLoop.adapt(source.getWorld(), actor, turns, task, provider, operator)
                                            : AgentLoop.start(source.getWorld(), actor, turns, task, provider, operator);
                                    return feedback(source, message);
                                })))));
    }

    private static int skill(CommandContext<ServerCommandSource> context, boolean run, int version) {
        var args = new JsonObject();
        args.addProperty("name", StringArgumentType.getString(context, "skill"));
        if (version > 0) {
            args.addProperty("version", version);
        }
        return withActor(context, (source, actor) -> {
            if (run) {
                AgentTools.require(!AgentLoop.isRunning(source.getWorld(), actor.getUuid()),
                        "Stop the actor's model run before starting a skill directly.");
            }
            var result = AgentTools.call(source.getWorld(), actor, run ? "run_skill" : "find_skills", args, false);
            if (!result.ok()) {
                return error(source, result.message());
            }
            if (run) {
                return feedback(source, result.message());
            }
            var skill = result.data().getAsJsonObject();
            var plan = new AgentDebug.Plan(skill.get("name").getAsString() + " v" + skill.get("version").getAsInt(),
                    skill.get("description").getAsString(), skill.get("program").toString());
            return showPlan(source, actor.getName().getString(), "Saved skill", plan);
        });
    }

    private static int listSkills(CommandContext<ServerCommandSource> context) {
        return withActor(context, (source, actor) -> {
            var entries = AgentSkills.list(source.getWorld(), actor);
            var names = new ArrayList<String>();
            entries.forEach(entry -> {
                var skill = entry.getAsJsonObject();
                names.add(skill.get("name").getAsString() + " v" + skill.get("version").getAsInt());
            });
            return feedback(source, names.isEmpty() ? "No learned skills yet." : "Learned skills · " + String.join(", ", names));
        });
    }

    private static int view(CommandContext<ServerCommandSource> context) {
        return withActor(context, (source, actor) -> {
            var view = AgentVision.observe(source.getWorld(), actor);
            LOGGER.info("[Agent view] {} {}", actor.getName().getString(), view);
            var pose = view.getAsJsonObject("pose");
            return feedback(source, actor.getName().getString() + " · Yaw " + pose.get("yaw").getAsFloat()
                    + "°, pitch " + pose.get("pitch").getAsFloat() + "°. Full view in the run log.");
        });
    }

    private static int status(CommandContext<ServerCommandSource> context) {
        return withActor(context, (source, actor) -> {
            var world = source.getWorld();
            var id = actor.getUuid();
            var loop = AgentLoop.status(world, id);
            var skill = AgentSkills.status(world, id);
            if (loop != null) {
                feedback(source, "Model · " + loop);
            }
            if (loop == null || !skill.data().isJsonNull()) {
                feedback(source, "Skill · " + skill.message());
            }
            return 1;
        });
    }

    private static int stop(CommandContext<ServerCommandSource> context) {
        return withActor(context, (source, actor) -> feedback(source, AgentLoop.stop(source.getWorld(), actor.getUuid())
                ? "Stopped " + actor.getName().getString() + "." : "The actor is idle."));
    }

    private static int debug(CommandContext<ServerCommandSource> context, DebugAction action) {
        var source = context.getSource();
        String label = StringArgumentType.getString(context, "actor");
        UUID id = findActor(source, label);
        if (id == null) {
            source.sendError(Text.literal("Choose a unique actor within 32 blocks, or use its UUID."));
            return 0;
        }
        var debug = AgentDebug.state(source.getWorld(), id);
        return switch (action) {
            case PLAN -> showPlan(source, label, "Last submission", debug.draft);
            case RUN -> showPlan(source, label, "Last execution", debug.run);
            case TRACE -> showTrace(source, label, debug);
            case TRACE_ON, TRACE_OFF -> {
                debug.tracing = action == DebugAction.TRACE_ON;
                String message = debug.tracing ? "Tracing enabled for " + label + "."
                        : "Tracing disabled for " + label + ".";
                source.sendFeedback(() -> Text.literal(message), false);
                yield 1;
            }
        };
    }

    private static int showPlan(ServerCommandSource source, String actor, String heading, AgentDebug.Plan plan) {
        if (plan == null) {
            source.sendError(Text.literal(heading + " unavailable for " + actor + " in this session."));
            return 0;
        }
        String summary = "[Agent debug] " + actor + " · " + heading + " · " + plan.label() + " · " + plan.status();
        LOGGER.info("{}\n{}", summary, plan.source());
        var feedback = Text.literal(summary + " Pseudocode in the run log.");
        if (source.getEntity() instanceof ServerPlayerEntity) {
            feedback.append(Text.literal(" [Copy pseudocode]").styled(style -> style
                    .withColor(Formatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, plan.source()))));
        }
        source.sendFeedback(() -> feedback, false);
        return 1;
    }

    private static int showTrace(ServerCommandSource source, String actor, AgentDebug.State debug) {
        if (debug.events.isEmpty()) {
            String hint = debug.tracing ? "Run an input skill to collect events."
                    : "Enable tracing with /agent debug " + actor + " trace on before running a skill.";
            source.sendFeedback(() -> Text.literal("No trace events for " + actor + ". " + hint), false);
            return 0;
        }
        int count = debug.events.size();
        String summary = "[Agent debug] " + actor + " · " + debug.run.label() + " · "
                + count + " trace events. " + debug.run.status();
        LOGGER.info("{}\n{}", summary, String.join("\n", debug.events));
        source.sendFeedback(() -> Text.literal(summary + " Full trace in the run log."), false);
        for (var event : debug.events.subList(Math.max(0, count - 4), count)) {
            source.sendFeedback(() -> Text.literal(event), false);
        }
        return count;
    }

    private static int demo(CommandContext<ServerCommandSource> context, boolean stop, boolean actor) {
        var source = context.getSource();
        var world = source.getWorld();
        var id = actor ? findActor(source, StringArgumentType.getString(context, "subject")) : null;
        var subject = actor ? (id == null ? null : world.getEntity(id)) : source.getEntity();
        if (actor ? !AgentTools.isActor(subject) : !(subject instanceof ServerPlayerEntity)) {
            source.sendError(Text.literal(actor ? "Choose a nearby agent by name or UUID." : "Record from the 3D player or name an agent."));
            return 0;
        }
        try {
            var message = stop ? AgentDemonstrations.stop(world, subject.getUuid())
                    : AgentDemonstrations.start(world, subject, StringArgumentType.getString(context, "name"));
            source.sendFeedback(() -> Text.literal(message), false);
            return 1;
        } catch (AgentTools.ToolFailure failure) {
            source.sendError(Text.literal(failure.getMessage()));
            return 0;
        }
    }

    private static int withActor(CommandContext<ServerCommandSource> context,
                                 BiFunction<ServerCommandSource, VillagerEntity, Integer> action) {
        var source = context.getSource();
        var id = findActor(source, StringArgumentType.getString(context, "actor"));
        if (id == null || !(source.getWorld().getEntity(id) instanceof VillagerEntity actor) || !AgentTools.isActor(actor)) {
            return error(source, "Choose a unique actor within 32 blocks, or use its UUID.");
        }
        try {
            return action.apply(source, actor);
        } catch (IllegalStateException | AgentTools.ToolFailure failure) {
            return error(source, failure.getMessage());
        }
    }

    private static int feedback(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal(message), false);
        return 1;
    }

    private static int error(ServerCommandSource source, String message) {
        source.sendError(Text.literal(message));
        return 0;
    }

    private static UUID findActor(ServerCommandSource source, String label) {
        try {
            return UUID.fromString(label);
        } catch (IllegalArgumentException ignored) {
            var world = source.getWorld();
            var position = source.getPosition();
            var matches = world.getEntitiesByClass(VillagerEntity.class, new Box(position, position).expand(32),
                    actor -> AgentTools.isActor(actor) && actor.squaredDistanceTo(position) <= 32 * 32
                            && actor.getName().getString().equalsIgnoreCase(label));
            if (matches.size() > 1) {
                int arena = AgentArena.at(world, BlockPos.ofFloored(position));
                matches = matches.stream().filter(actor -> AgentArena.actorArena(actor) == arena).toList();
            }
            return matches.size() == 1 ? matches.get(0).getUuid() : null;
        }
    }
}
