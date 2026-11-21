package org.minecraftagents.tools;

import com.google.gson.GsonBuilder;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Runs the selected actors for a fixed number of normal Minecraft days and saves at the endpoint. */
public final class AgentTrial {
    private static final int DAY_TICKS = 24_000;
    static final int DEFAULT_DAYS = 5;
    private static final int STATUS_INTERVAL = 1_200;
    private static final int TICKET_RADIUS = 2;
    private static final ChunkTicketType<UUID> ACTOR_TICKET =
            ChunkTicketType.create("minecraft_agents_trial", UUID::compareTo);
    private static final String GOAL = "Survive, thrive, and learn with your companions. Share what you learn. "
            + "Mobs arrive at night.";
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentTrial.class);
    private static final Map<ServerWorld, AgentTrial> ACTIVE = Collections.synchronizedMap(new HashMap<>());

    private record ActorResult(String name, boolean alive, float health, AgentTools.Position position,
                               List<AgentInventory.Slot> inventory) {
    }

    private record Report(String world, long seed, String outcome, int targetDays, long elapsedTicks,
                          List<ActorResult> actors) {
    }

    private static final class Member {
        private final UUID id;
        private VillagerEntity actor;
        private ChunkPos ticket;
        private boolean alive = true;

        private Member(VillagerEntity actor) {
            this.id = actor.getUuid();
            this.actor = actor;
        }

        private void update(ServerWorld world) {
            if (!alive) {
                return;
            }
            if (world.getEntity(id) instanceof VillagerEntity loaded) {
                actor = loaded;
            }
            var removal = actor.getRemovalReason();
            if (actor.getHealth() <= 0 || (removal != null && removal.shouldDestroy())) {
                alive = false;
                release(world);
                LOGGER.info("[Agent trial] {} died at {}.", actor.getName().getString(), actor.getBlockPos().toShortString());
                return;
            }
            ChunkPos chunk = actor.getChunkPos();
            if (!chunk.equals(ticket)) {
                world.getChunkManager().addTicket(ACTOR_TICKET, chunk, TICKET_RADIUS, id);
                release(world);
                ticket = chunk;
            }
        }

        private void release(ServerWorld world) {
            if (ticket != null) {
                world.getChunkManager().removeTicket(ACTOR_TICKET, ticket, TICKET_RADIUS, id);
                ticket = null;
            }
        }

        private ActorResult snapshot() {
            return new ActorResult(actor.getName().getString(), alive, alive ? actor.getHealth() : 0,
                    AgentTools.Position.of(actor.getBlockPos()),
                    alive ? AgentInventory.slots(actor.getInventory()) : List.of());
        }
    }

    private final long startedAt;
    private final int targetDays;
    private final long durationTicks;
    private final List<Member> members;
    private final AgentModelClient client;

    private AgentTrial(ServerWorld world, List<Member> members, AgentModelClient client, int days) {
        this.startedAt = world.getTime();
        this.targetDays = days;
        this.durationTicks = (long) DAY_TICKS * days;
        this.members = members;
        this.client = client;
    }

    static String start(ServerWorld world, List<UUID> ids, int days) {
        if (ACTIVE.containsKey(world)) {
            throw new IllegalStateException("This world already has an active trial.");
        }
        if ((ids.size() != 2 && ids.size() != 3) || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalStateException("Choose two or three distinct living actors.");
        }
        var members = ids.stream().map(id -> {
            if (!(world.getEntity(id) instanceof VillagerEntity actor) || !AgentTools.isActor(actor)
                    || AgentLoop.isRunning(world, id)) {
                throw new IllegalStateException("Choose living actors with no active model loops.");
            }
            return new Member(actor);
        }).toList();
        var client = AgentModelClient.load(AgentModelClient.Provider.OPENAI);
        var trial = new AgentTrial(world, members, client, days);
        ACTIVE.put(world, trial);
        for (Member member : members) {
            member.update(world);
            AgentLoop.startTrial(world, member.id, client, GOAL);
        }
        String message = "Started the " + days + "-day trial with " + client.model() + ". " + trial.statusText(world);
        LOGGER.info("[Agent trial] {}", message);
        return message;
    }

    static String status(ServerWorld world) {
        AgentTrial trial = ACTIVE.get(world);
        return trial == null ? "No active trial in this world." : trial.statusText(world);
    }

    static long remainingTicks(ServerWorld world) {
        AgentTrial trial = ACTIVE.get(world);
        return trial == null ? 0 : Math.max(0, trial.durationTicks - trial.elapsed(world));
    }

    static void stop(ServerWorld world) {
        AgentTrial trial = ACTIVE.get(world);
        if (trial == null) {
            throw new IllegalStateException("No active trial in this world.");
        }
        trial.finish(world, "stopped by operator");
    }

    public static void tick(ServerWorld world) {
        AgentTrial trial = ACTIVE.get(world);
        if (trial == null) {
            return;
        }
        trial.members.forEach(member -> member.update(world));
        long elapsed = trial.elapsed(world);
        if (trial.members.stream().noneMatch(member -> member.alive)) {
            trial.finish(world, "all actors died");
        } else if (elapsed >= trial.durationTicks) {
            trial.finish(world, "day limit reached");
        } else if (elapsed % STATUS_INTERVAL == 0) {
            LOGGER.info("[Agent trial] {}", trial.statusText(world));
            for (Member member : trial.members) {
                if (member.alive && AgentTools.isActor(member.actor) && !AgentLoop.isRunning(world, member.id)) {
                    AgentLoop.startTrial(world, member.id, trial.client, GOAL);
                }
            }
        }
    }

    public static void clear(ServerWorld world) {
        AgentTrial trial = ACTIVE.remove(world);
        if (trial != null) {
            trial.members.forEach(member -> member.release(world));
        }
    }

    private long elapsed(ServerWorld world) {
        return world.getTime() - startedAt;
    }

    private String statusText(ServerWorld world) {
        var summary = members.stream().map(member -> {
            ActorResult actor = member.snapshot();
            return actor.name() + (actor.alive() ? " " + actor.health() + " HP at " + actor.position() : " dead");
        }).toList();
        return "Tick " + elapsed(world) + "/" + durationTicks + ". " + String.join(", ", summary) + ".";
    }

    private void finish(ServerWorld world, String outcome) {
        ACTIVE.remove(world);
        for (Member member : members) {
            AgentLoop.status(world, member.id, true);
            member.release(world);
        }
        var server = world.getServer();
        var report = new Report(server.getSaveProperties().getLevelName(), world.getSeed(), outcome, targetDays,
                elapsed(world), members.stream().map(Member::snapshot).toList());
        LOGGER.info("[Agent trial] Ending because {}. {}", outcome, statusText(world));
        server.saveAll(true, true, true);
        try {
            var path = server.getSavePath(WorldSavePath.ROOT).resolve("agent-trial-result.json");
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            LOGGER.info("[Agent trial] Saved result to {}.", path);
        } catch (IOException failure) {
            LOGGER.error("[Agent trial] Could not write the result file.", failure);
        }
        server.stop(false);
    }
}
