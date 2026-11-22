package org.minecraftagents.tools;

import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.AutomaticItemPlacementContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.minecraftagents.tools.AgentTools.number;
import static org.minecraftagents.tools.AgentTools.require;

/** Virtual keyboard and mouse state for one actor, advanced by simulation ticks. */
public final class AgentInput {
    private static final int MAX_HOLD = 160;
    private static final double WALK_SPEED = 0.20;
    private static final Set<String> MOVEMENT_KEYS = Set.of("w", "a", "s", "d", "space");
    private static final Map<ServerWorld, Map<UUID, Control>> WORLDS = new HashMap<>();

    private AgentInput() {
    }

    static AgentTools.Result press(ServerWorld world, VillagerEntity actor, JsonObject args) {
        String key = args.get("key").getAsString();
        int ticks = ticks(args, 5);
        boolean slot = key.matches("[1-9]");
        Set<String> keys = slot || key.equals("none") ? Set.of() : new HashSet<>(Arrays.asList(key.split("\\+", -1)));
        require(MOVEMENT_KEYS.containsAll(keys), "Use w, a, s, d, space, w+space, none to wait, or 1–9.");
        var control = available(world, actor);
        AgentDemonstrations.agentInput(world, actor, "press_key", args);
        if (slot) {
            control.slot = key.charAt(0) - '1';
        } else {
            control.hold = new Hold(keys, ticks);
        }
        return AgentTools.ok("Keyboard input accepted.", null);
    }

    static AgentTools.Result mouse(ServerWorld world, VillagerEntity actor, JsonObject args) {
        int yaw = angle(args, "yaw", 360);
        boolean absolutePitch = args.has("pitch_to");
        require(!absolutePitch || !args.has("pitch"), "Use pitch or pitch_to for a mouse action.");
        int pitch = absolutePitch ? angle(args, "pitch_to", 90) : angle(args, "pitch", 180);
        int ticks = ticks(args, 1);
        String button = args.has("button") ? args.get("button").getAsString() : "";
        require(button.isEmpty() || button.equals("left") || button.equals("right"), "Use left or right mouse button.");
        require(args.has("yaw") || args.has("pitch") || absolutePitch || !button.isEmpty(), "Supply a mouse movement or button.");
        require(!args.has("ticks") || button.equals("left"), "Use ticks with a left button hold.");
        var control = available(world, actor);
        AgentDemonstrations.agentInput(world, actor, "mouse", args);
        float facing = MathHelper.wrapDegrees(actor.getYaw() + yaw);
        actor.setYaw(facing);
        actor.setHeadYaw(facing);
        actor.setBodyYaw(facing);
        actor.setPitch(absolutePitch ? pitch : MathHelper.clamp(actor.getPitch() + pitch, -90, 90));
        if (button.equals("left")) {
            control.hold = new Hold(ticks);
        } else if (button.equals("right")) {
            var hit = AgentVision.target(world, actor, AgentVision.REACH);
            if (hit.getType() == HitResult.Type.BLOCK && world.getWorldBorder().contains(hit.getBlockPos())) {
                useHeldBlock(world, actor, hit, control.slot);
            }
            actor.swingHand(Hand.MAIN_HAND);
        }
        return AgentTools.ok("Mouse input accepted.", null);
    }

    private static int angle(JsonObject args, String name, int maximum) {
        int angle = args.has(name) ? number(args, name) : 0;
        require(angle >= -maximum && angle <= maximum, "Use " + -maximum + " to " + maximum + " degrees for " + name + ".");
        return angle;
    }

    private static int ticks(JsonObject args, int fallback) {
        int ticks = args.has("ticks") ? number(args, "ticks") : fallback;
        require(ticks >= 1 && ticks <= MAX_HOLD, "Hold input for 1 to " + MAX_HOLD + " ticks.");
        return ticks;
    }

    private static Control available(ServerWorld world, VillagerEntity actor) {
        UUID id = actor.getUuid();
        require(!isActive(world, id), "Wait for the current input to finish.");
        return WORLDS.computeIfAbsent(world, key -> new HashMap<>()).computeIfAbsent(id, key -> new Control());
    }

    static boolean isActive(ServerWorld world, UUID id) {
        var controls = WORLDS.get(world);
        var control = controls == null ? null : controls.get(id);
        return control != null && control.hold != null;
    }

    static void cancel(ServerWorld world, UUID id) {
        var controls = WORLDS.get(world);
        var control = controls == null ? null : controls.get(id);
        if (control != null && control.hold != null) {
            control.hold.clearCracks(world);
            control.hold = null;
        }
    }

    public static void tick(ServerWorld world) {
        var controls = WORLDS.get(world);
        if (controls == null) {
            return;
        }
        var iterator = controls.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var control = entry.getValue();
            if (!(world.getEntity(entry.getKey()) instanceof VillagerEntity actor) || !AgentTools.isActor(actor)) {
                cancel(world, entry.getKey());
                iterator.remove();
                continue;
            }
            if (control.hold != null) {
                control.hold.tick(world, actor, control.slot);
                if (--control.hold.left == 0) {
                    cancel(world, entry.getKey());
                }
            }
            for (var item : world.getEntitiesByClass(ItemEntity.class, actor.getBoundingBox().expand(0.4, 0.2, 0.4),
                    entity -> entity.isAlive() && !entity.cannotPickup())) {
                var stack = item.getStack();
                int collected = AgentInventory.insert(actor.getInventory(), stack, stack.getCount());
                if (collected > 0) {
                    AgentDebug.effect(world, actor, "Picked up " + collected + " " + Registries.ITEM.getId(stack.getItem()));
                }
                if (collected == stack.getCount()) {
                    item.discard();
                } else if (collected > 0) {
                    item.setStack(stack.copyWithCount(stack.getCount() - collected));
                }
            }
        }
    }

    public static void clear(ServerWorld world) {
        WORLDS.remove(world);
    }

    private static boolean canMove(ServerWorld world, VillagerEntity actor, Vec3d delta) {
        BlockPos destination = BlockPos.ofFloored(actor.getPos().add(delta));
        return !world.isOutOfHeightLimit(destination) && world.isChunkLoaded(ChunkPos.toLong(destination))
                && world.getWorldBorder().contains(actor.getBoundingBox().offset(delta));
    }

    private static void useHeldBlock(ServerWorld world, VillagerEntity actor, BlockHitResult hit, int slot) {
        var inventory = actor.getInventory();
        BlockPos pos = hit.getBlockPos().offset(hit.getSide());
        if (slot >= inventory.size() || world.isOutOfHeightLimit(pos)
                || !world.isChunkLoaded(ChunkPos.toLong(pos)) || !world.getWorldBorder().contains(pos)) {
            return;
        }
        ItemStack stack = inventory.getStack(slot);
        if (stack.getItem() instanceof BlockItem item) {
            var placement = new AutomaticItemPlacementContext(world, pos, actor.getHorizontalFacing(), stack, hit.getSide());
            if (item.place(placement).isAccepted()) {
                inventory.markDirty();
            }
        }
    }

    private static boolean breakHeldBlock(ServerWorld world, VillagerEntity actor, BlockPos pos, BlockState block,
                                          ItemStack tool, boolean suitable) {
        var blockEntity = world.getBlockEntity(pos);
        if (!world.breakBlock(pos, false, actor)) {
            return false;
        }
        if (suitable) {
            Block.dropStacks(block, world, pos, blockEntity, actor, tool);
            if (tool.isDamageable() && block.getHardness(world, pos) > 0) {
                if (tool.damage(1, actor.getRandom(), null)) {
                    tool.decrement(1);
                    tool.setDamage(0);
                }
                actor.getInventory().markDirty();
            }
        }
        return true;
    }

    private static final class Control {
        private int slot;
        private Hold hold;
    }

    private static final class Hold {
        private final Set<String> keys;
        private int left;
        private int breakingId;
        private BlockPos breaking;
        private BlockState state;
        private float progress;
        private int shownStage = -1;

        private Hold(Set<String> keys, int ticks) {
            this.keys = keys;
            left = ticks;
        }

        private Hold(int ticks) {
            this(null, ticks);
        }

        private void tick(ServerWorld world, VillagerEntity actor, int slot) {
            if (keys == null) {
                hit(world, actor, slot);
                return;
            }
            double forward = amount("w", "s");
            double right = amount("d", "a");
            var facing = AgentVision.direction(actor.getYaw(), 0);
            var sideways = new Vec3d(-facing.z, 0, facing.x);
            Vec3d delta = facing.multiply(forward).add(sideways.multiply(right)).normalize().multiply(WALK_SPEED);
            if (keys.contains("space") && actor.isOnGround()) {
                actor.setVelocity(actor.getVelocity().add(0, 0.42, 0));
                actor.setOnGround(false);
            }
            if (delta.lengthSquared() > 0 && canMove(world, actor, delta)) {
                actor.move(MovementType.SELF, delta);
            }
        }

        private int amount(String positive, String negative) {
            return (keys.contains(positive) ? 1 : 0) - (keys.contains(negative) ? 1 : 0);
        }

        private void hit(ServerWorld world, VillagerEntity actor, int slot) {
            var hit = AgentVision.target(world, actor, AgentVision.REACH);
            var pos = hit.getBlockPos();
            if (hit.getType() != HitResult.Type.BLOCK || !world.getWorldBorder().contains(pos)
                    || !world.isChunkLoaded(ChunkPos.toLong(pos))) {
                clearCracks(world);
                return;
            }
            var block = world.getBlockState(pos);
            float hardness = block.getHardness(world, pos);
            if (hardness < 0 || block.isAir()) {
                clearCracks(world);
                return;
            }
            if (!pos.equals(breaking) || block != state) {
                clearCracks(world);
                breaking = pos;
                breakingId = actor.getId();
                state = block;
                actor.swingHand(Hand.MAIN_HAND);
            }
            ItemStack tool = slot < actor.getInventory().size() ? actor.getInventory().getStack(slot) : ItemStack.EMPTY;
            boolean suitable = !block.isToolRequired() || tool.isSuitableFor(block);
            progress += hardness == 0 ? 1 : tool.getMiningSpeedMultiplier(block) / hardness / (suitable ? 30 : 100);
            if (progress >= 1 - 0.000001f) {
                if (breakHeldBlock(world, actor, pos, block, tool, suitable)) {
                    AgentDebug.effect(world, actor, "Broke " + Registries.BLOCK.getId(block.getBlock())
                            + " at " + pos.toShortString());
                }
                clearCracks(world);
            } else {
                int stage = (int) (progress * 10);
                if (stage != shownStage) {
                    shownStage = stage;
                    world.setBlockBreakingInfo(breakingId, breaking, stage);
                }
            }
        }

        private void clearCracks(ServerWorld world) {
            if (breaking != null) {
                world.setBlockBreakingInfo(breakingId, breaking, -1);
            }
            breaking = null;
            state = null;
            progress = 0;
            shownStage = -1;
        }
    }
}
