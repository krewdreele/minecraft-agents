package org.minecraftagents.tools;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.minecraftagents.tools.AgentTools.require;

/** One local ROCKET-3 trial, with world changes owned by the server tick. */
public final class AgentRocket {
    public static final int IMAGE_SIZE = 224;
    private static final int MASK_BYTES = IMAGE_SIZE * IMAGE_SIZE;
    private static final int RGB_BYTES = MASK_BYTES * 3;
    private static final int CONTROL_BYTES = Float.BYTES * 2 + Short.BYTES;
    private static final int MAX_TICKS = 1200;
    private static final int MAX_FRAME_AGE = 3;
    private static final double APPROACH_DISTANCE = 3;
    private static final Map<ServerWorld, Map<UUID, String>> RESULTS = new HashMap<>();
    private static volatile Attempt active;

    private AgentRocket() {
    }

    public record Capture(UUID player, UUID actor, RegistryKey<World> dimension, BlockPos target,
                          UUID targetEntity, boolean goal, CompletableFuture<byte[]> frame) {
    }

    // Wire order shared with BUTTONS in the Python worker.
    enum Button { FORWARD, BACK, LEFT, RIGHT, JUMP, ATTACK, USE }

    enum Task {
        OAK_LOG("collect oak log", 2),
        APPROACH("approach", 6),
        BREAK("break", 2),
        COLLECT("collect", 2),
        USE("use", 3);

        private final String command;
        private final int event;

        Task(String command, int event) {
            this.command = command;
            this.event = event;
        }

        String command() {
            return command;
        }

        boolean mines() {
            return this == BREAK || collects();
        }

        boolean collects() {
            return this == COLLECT || this == OAK_LOG;
        }

        static Task fromInstruction(String instruction) {
            String phrase = instruction.strip().toLowerCase(Locale.ROOT).replaceFirst("[.!?]+$", "")
                    .replaceAll("\\s+", " ");
            return switch (phrase) {
                case "approach", "approach this block", "walk to this block", "go to the marked block",
                        "approach this mob", "walk to this mob", "go to the marked mob", "go to the target" -> APPROACH;
                case "break", "break this block", "break the marked block", "mine this block" -> BREAK;
                case "collect", "collect the drop", "collect this block's drops", "mine and collect this block" -> COLLECT;
                case "use", "use this block", "use the marked block", "toggle this block" -> USE;
                default -> throw new AgentTools.ToolFailure(
                        "Aim at the target and choose approach, break, collect, or use. For example: task walk to this block.");
            };
        }
    }

    record Action(float pitch, float yaw, int buttons) {
        private static final Action NEUTRAL = new Action(0, 0, 0);
        private static final int BUTTON_LIMIT = 1 << Button.values().length;

        boolean pressed(Button button) {
            return (buttons & (1 << button.ordinal())) != 0;
        }
    }

    public static Capture capture() {
        var attempt = active;
        return attempt == null ? null : attempt.capture;
    }

    static String start(ServerWorld world, VillagerEntity actor, ServerPlayerEntity player, Task task, int maskPercent) {
        require(!world.getServer().isDedicated() && player != null && world.getServer().isHost(player.getGameProfile()),
                "Start a ROCKET-3 trial from the local graphical singleplayer client.");
        require(active == null, "A ROCKET-3 trial is already running.");
        UUID id = actor.getUuid();
        require(!AgentLoop.isRunning(world, id) && !AgentSkills.isActive(world, id) && !AgentInput.isActive(world, id),
                "Wait for the actor to finish its current work.");
        var hit = AgentVision.target(world, player, AgentVision.REACH);
        if (task == Task.APPROACH) {
            var mob = pointedMob(player, hit);
            if (mob != null) {
                require(mob != actor, "Aim at a mob other than the controlled actor.");
                require(actor.getBlockPos().isWithinDistance(mob.getBlockPos(), 32), "Choose a mob within 32 blocks of the actor.");
                active = new Attempt(world, actor, player, mob.getBlockPos(), null, mob.getUuid(), task, maskPercent);
                return "ROCKET-3 approach target marked. Hold your view on the mob as chat closes.";
            }
        }
        require(hit.getType() == HitResult.Type.BLOCK, "Point your crosshair at a nearby block to mark it.");
        BlockPos target = hit.getBlockPos();
        BlockState state = world.getBlockState(target);
        require(!state.isAir() && world.getWorldBorder().contains(target), "Mark a loaded block inside the world border.");
        require(actor.getBlockPos().isWithinDistance(target, 32), "Choose a block within 32 blocks of the actor.");
        if (task == Task.OAK_LOG) {
            require(state.isOf(Blocks.OAK_LOG), "Mark an oak log or choose a task after the actor name.");
            require(hasLogSpace(actor), "The actor needs space for an oak log.");
        }
        if (task.mines()) {
            require(state.getHardness(world, target) >= 0, "Choose a breakable block.");
        }
        if (task.collects() && state.isToolRequired()) {
            require(AgentInput.heldStack(world, actor).isSuitableFor(state),
                    "The actor needs to hold a suitable tool to collect this block's drops.");
        }
        if (task == Task.USE) {
            require(state.getBlock() instanceof LeverBlock || state.getBlock() instanceof ButtonBlock
                            || DoorBlock.canOpenByHand(state),
                    "Mark a lever, a button, or a door that opens by hand.");
        }
        active = new Attempt(world, actor, player, target, state.getBlock(), null, task, maskPercent);
        return "ROCKET-3 " + task.command() + " target marked at " + target.toShortString()
                + ". Hold your view on the block as chat closes.";
    }

    private static MobEntity pointedMob(ServerPlayerEntity player, BlockHitResult blockHit) {
        Vec3d eye = player.getEyePos();
        Vec3d ray = blockHit.getPos().subtract(eye);
        var hit = ProjectileUtil.raycast(player, eye, blockHit.getPos(), player.getBoundingBox().stretch(ray).expand(1),
                candidate -> candidate instanceof MobEntity && candidate.isAlive() && candidate.canHit()
                        && !candidate.isInvisible(), Math.min(ray.lengthSquared(), APPROACH_DISTANCE * APPROACH_DISTANCE));
        return hit == null ? null : (MobEntity) hit.getEntity();
    }

    private static boolean hasLogSpace(VillagerEntity actor) {
        var inventory = actor.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isEmpty() || (stack.isOf(Items.OAK_LOG) && stack.getCount() < stack.getMaxCount())) {
                return true;
            }
        }
        return false;
    }

    static boolean isActive(ServerWorld world, UUID actor) {
        var attempt = active;
        return attempt != null && attempt.world == world && attempt.actor.equals(actor);
    }

    static String status(ServerWorld world, UUID actor) {
        if (isActive(world, actor)) {
            var attempt = active;
            String state = attempt.capture.goal() ? "Capturing marked target"
                    : attempt.mined && attempt.task.collects() ? "Waiting for a drop from the marked block" : "Running";
            return state + attempt.metrics();
        }
        return RESULTS.getOrDefault(world, Map.of()).get(actor);
    }

    static boolean stop(ServerWorld world, UUID actor) {
        if (!isActive(world, actor)) {
            return false;
        }
        active.finish("Stopped by the operator");
        return true;
    }

    public static void clear(ServerWorld world) {
        if (active != null && active.world == world) {
            active.finish("World closed");
        }
        RESULTS.remove(world);
    }

    public static void tick(ServerWorld world) {
        var attempt = active;
        if (attempt == null || attempt.world != world) {
            return;
        }
        if (!(world.getEntity(attempt.actor) instanceof VillagerEntity actor) || !actor.isAlive()
                || !AgentTools.isActor(actor) || world.getPlayerByUuid(attempt.player) == null) {
            attempt.finish("Actor or operator unavailable");
        } else {
            attempt.tick(actor);
        }
    }

    static void mined(ServerWorld world, UUID actor, Set<UUID> drops) {
        if (isActive(world, actor)) {
            active.mined = true;
            active.drops = drops;
        }
    }

    static void pickedUp(ServerWorld world, UUID actor, UUID drop) {
        if (isActive(world, actor) && active.drops.contains(drop)) {
            active.collected = true;
        }
    }

    private static final class Attempt {
        private static final HttpClient HTTP = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();

        private final ServerWorld world;
        private final UUID actor;
        private final UUID player;
        private final BlockPos target;
        private final Block targetBlock;
        private final UUID targetEntity;
        private final Task task;
        private final int maskPercent;
        private final URI endpoint;
        private volatile Capture capture;
        private CompletableFuture<HttpResponse<byte[]>> response;
        private Action previous = Action.NEUTRAL;
        private int ticks;
        private int frameTick;
        private int applied;
        private int late;
        private int offTarget;
        private int lastActionTick;
        private boolean mined;
        private boolean used;
        private Set<UUID> drops = Set.of();
        private boolean collected;

        private Attempt(ServerWorld world, VillagerEntity actor, ServerPlayerEntity player, BlockPos target,
                        Block targetBlock, UUID targetEntity, Task task, int maskPercent) {
            this.world = world;
            this.actor = actor.getUuid();
            this.player = player.getUuid();
            this.target = target.toImmutable();
            this.targetBlock = targetBlock;
            this.targetEntity = targetEntity;
            this.task = task;
            this.maskPercent = maskPercent;
            String portValue = System.getenv().getOrDefault("ROCKET3_PORT", "8766");
            int port;
            try {
                port = Integer.parseInt(portValue);
            } catch (NumberFormatException failure) {
                throw new AgentTools.ToolFailure("Set ROCKET3_PORT to a port from 1 to 65535.");
            }
            require(port > 0 && port <= 65535, "Set ROCKET3_PORT to a port from 1 to 65535.");
            endpoint = URI.create("http://127.0.0.1:" + port + "/v1/step/" + UUID.randomUUID());
            requestFrame(true);
        }

        private void tick(VillagerEntity villager) {
            ticks++;
            String success = outcome(villager);
            if (success != null) {
                finish(success);
                return;
            }
            if (ticks >= MAX_TICKS || (capture.goal() && ticks >= 200)) {
                finish(capture.goal() ? "Timed out capturing the marked target" : "Trial timed out");
                return;
            }
            if (!mined && targetLocation() == null) {
                finish("The marked target is no longer available");
                return;
            }
            try {
                if (response != null && response.isDone()) {
                    applyReply(villager);
                    success = outcome(villager);
                    if (success != null) {
                        finish(success);
                        return;
                    }
                    requestControlFrame(null);
                }
                if (ticks - lastActionTick > MAX_FRAME_AGE) {
                    AgentInput.releaseRocketAttack(world, actor);
                }
                if (response == null && capture.frame().isDone()) {
                    requestControlFrame(capture.frame().join());
                }
            } catch (CompletionException failure) {
                var cause = failure.getCause() == null ? failure : failure.getCause();
                if (cause instanceof ConnectException) {
                    finish("Could not connect to the ROCKET-3 worker at " + endpoint.getAuthority());
                } else {
                    var detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                    finish("ROCKET-3 worker or capture failed: " + detail);
                }
            } catch (IllegalArgumentException failure) {
                finish(failure.getMessage());
            }
        }

        private String outcome(VillagerEntity villager) {
            return switch (task) {
                case APPROACH -> {
                    var location = targetLocation();
                    yield location != null && villager.squaredDistanceTo(location) <= APPROACH_DISTANCE * APPROACH_DISTANCE
                            ? "Reached the marked target" : null;
                }
                case BREAK -> mined ? "Broke the marked block" : null;
                case OAK_LOG, COLLECT -> collected ? "Collected a drop from the marked block" : null;
                case USE -> used ? "Used the marked block" : null;
            };
        }

        private Vec3d targetLocation() {
            if (targetEntity != null) {
                var mob = world.getEntity(targetEntity);
                return mob != null && mob.isAlive() ? mob.getPos() : null;
            }
            return world.isChunkLoaded(ChunkPos.toLong(target)) && world.getBlockState(target).isOf(targetBlock)
                    ? Vec3d.ofCenter(target) : null;
        }

        private void requestFrame(boolean goal) {
            frameTick = ticks;
            capture = new Capture(player, actor, world.getRegistryKey(), target, targetEntity, goal, new CompletableFuture<>());
        }

        private void requestControlFrame(byte[] goalImage) {
            requestFrame(false);
            Action last = previous;
            response = capture.frame().thenComposeAsync(pixels -> sendFrame(pixels, goalImage, last));
        }

        private CompletableFuture<HttpResponse<byte[]>> sendFrame(byte[] pixels, byte[] goalImage, Action last) {
            int goalBytes = goalImage == null ? 0 : RGB_BYTES + MASK_BYTES + 1;
            var buffer = ByteBuffer.allocate(CONTROL_BYTES + RGB_BYTES + goalBytes);
            buffer.putFloat(last.pitch()).putFloat(last.yaw()).putShort((short) last.buttons()).put(pixels);
            if (goalImage != null) {
                buffer.put(goalImage);
                int maskOffset = buffer.position();
                int width = IMAGE_SIZE * maskPercent / 100;
                int start = (IMAGE_SIZE - width) / 2;
                for (int y = start; y < start + width; y++) {
                    for (int x = start; x < start + width; x++) {
                        buffer.put(maskOffset + y * IMAGE_SIZE + x, (byte) 1);
                    }
                }
                buffer.put(buffer.capacity() - 1, (byte) task.event);
            }
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(buffer.array())).build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        }

        private void applyReply(VillagerEntity villager) {
            var reply = response.join();
            if (reply.statusCode() != 200 || reply.body().length != CONTROL_BYTES) {
                throw new IllegalArgumentException("ROCKET-3 worker returned an invalid action (HTTP " + reply.statusCode() + ").");
            }
            var bytes = ByteBuffer.wrap(reply.body());
            var action = new Action(bytes.getFloat(), bytes.getFloat(), Short.toUnsignedInt(bytes.getShort()));
            if (!Float.isFinite(action.pitch()) || !Float.isFinite(action.yaw())
                    || Math.abs(action.pitch()) > 180 || Math.abs(action.yaw()) > 180
                    || action.buttons() >= Action.BUTTON_LIMIT) {
                throw new IllegalArgumentException("ROCKET-3 worker returned controls outside the supported range.");
            }
            if (ticks - frameTick <= MAX_FRAME_AGE) {
                applied++;
                lastActionTick = ticks;
                previous = action;
                if (AgentInput.rocketStep(world, villager, action, task.mines() && !mined ? target : null)) {
                    offTarget++;
                }
                if (task == Task.USE && action.pressed(Button.USE)) {
                    used = useTarget(villager);
                }
            } else {
                late++;
                previous = Action.NEUTRAL;
                AgentInput.releaseRocketAttack(world, actor);
            }
        }

        private boolean useTarget(VillagerEntity villager) {
            var hit = AgentVision.target(world, villager, AgentVision.REACH);
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) {
                return false;
            }
            var state = world.getBlockState(target);
            if (state.getBlock() instanceof LeverBlock lever) {
                boolean powered = lever.togglePower(state, world, target).get(Properties.POWERED);
                world.emitGameEvent(villager, powered ? GameEvent.BLOCK_ACTIVATE : GameEvent.BLOCK_DEACTIVATE, target);
            } else if (state.getBlock() instanceof ButtonBlock button && !state.get(Properties.POWERED)) {
                button.powerOn(state, world, target);
                world.emitGameEvent(villager, GameEvent.BLOCK_ACTIVATE, target);
            } else if (state.getBlock() instanceof DoorBlock door && DoorBlock.canOpenByHand(state)) {
                door.setOpen(villager, world, state, target, !door.isOpen(state));
            }
            return world.getBlockState(target) != state;
        }

        private String metrics() {
            return " · " + task.command() + " at " + target.toShortString() + ", " + ticks + " ticks, " + applied
                    + " actions, " + late + " late replies, " + offTarget + " off-target attacks blocked"
                    + (mined ? ", marked block mined." : ".");
        }

        private void finish(String result) {
            active = null;
            capture.frame().cancel(false);
            if (response != null) {
                response.cancel(true);
            }
            AgentInput.releaseRocketAttack(world, actor);
            String summary = result + metrics();
            RESULTS.computeIfAbsent(world, ignored -> new HashMap<>()).put(actor, summary);
            var operator = world.getPlayerByUuid(player);
            if (operator != null) {
                operator.sendMessage(Text.literal("ROCKET-3 · " + summary), false);
            }
        }
    }
}
