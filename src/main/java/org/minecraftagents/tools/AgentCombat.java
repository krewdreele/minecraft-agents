package org.minecraftagents.tools;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tick-driven, bounded melee actions for manually controlled actors. */
public final class AgentCombat {
    static final int MAX_SWINGS = 5;
    static final double TARGET_RANGE = 6;
    private static final int MAX_TICKS = 100;
    private static final int STALLED_TICKS = 10;
    private static final int SWING_INTERVAL = 12;
    private static final double STEP = 0.22;
    private static final float DAMAGE = 2;

    private static final Map<ServerWorld, Map<UUID, Attack>> WORLDS = Collections.synchronizedMap(new HashMap<>());

    private AgentCombat() {
    }

    record Status(int targetId, int requestedSwings, int swings, int hits, boolean active, String message) {
    }

    private static final class Attack {
        private final UUID targetUuid;
        private final int targetId;
        private final int requestedSwings;
        private final int weaponSlot;
        private int ticksLeft = MAX_TICKS;
        private int stalled;
        private int cooldown;
        private int swings;
        private int hits;
        private boolean active = true;
        private String message = "Attack in progress.";

        private Attack(LivingEntity target, int requestedSwings, int weaponSlot) {
            this.targetUuid = target.getUuid();
            this.targetId = target.getId();
            this.requestedSwings = requestedSwings;
            this.weaponSlot = weaponSlot;
        }

        private Status status() {
            return new Status(targetId, requestedSwings, swings, hits, active, message);
        }

        private void finish(String message) {
            this.active = false;
            this.message = message;
        }

        private void tick(ServerWorld world, VillagerEntity actor) {
            if (!(world.getEntity(targetUuid) instanceof LivingEntity target) || !target.isAlive()) {
                finish("The target is gone.");
                return;
            }
            if (!canTarget(world, actor, target)) {
                finish("The target is out of range or cannot be attacked.");
                return;
            }
            if (ticksLeft-- == 0) {
                finish("The attack timed out.");
                return;
            }
            if (cooldown > 0) {
                cooldown--;
            }

            Vec3d offset = target.getPos().subtract(actor.getPos());
            if (offset.horizontalLengthSquared() > 1.0E-6) {
                float yaw = (float) Math.toDegrees(Math.atan2(offset.z, offset.x)) - 90;
                AgentTools.face(actor, yaw);
            }
            if (actor.isInAttackRange(target) && actor.canSee(target)) {
                stalled = 0;
                if (cooldown == 0) {
                    swing(world, actor, target);
                }
                return;
            }
            if (approach(world, actor, offset)) {
                stalled = 0;
            } else if (++stalled >= STALLED_TICKS) {
                finish("The path to the target is blocked.");
            }
        }

        private void swing(ServerWorld world, VillagerEntity actor, LivingEntity target) {
            ItemStack weapon = weaponSlot < 0 ? ItemStack.EMPTY : actor.getInventory().getStack(weaponSlot);
            float damage = DAMAGE;
            if (weaponSlot >= 0) {
                if (weapon.isEmpty() || !(weapon.getItem() instanceof SwordItem sword)) {
                    finish("The sword in that slot is unavailable.");
                    return;
                }
                damage = 1 + sword.getAttackDamage();
            }
            actor.swingHand(Hand.MAIN_HAND);
            boolean hit = target.damage(actor.getDamageSources().mobAttack(actor), damage);
            if (hit) {
                hits++;
                if (!weapon.isEmpty()) {
                    weapon.getItem().postHit(weapon, target, actor);
                    actor.getInventory().markDirty();
                }
            }
            Vec3d point = target.getBoundingBox().getCenter();
            world.spawnParticles(ParticleTypes.SWEEP_ATTACK, point.x, point.y, point.z, 1, 0, 0, 0, 0);
            world.playSound(null, actor.getBlockPos(),
                    hit ? SoundEvents.ENTITY_PLAYER_ATTACK_STRONG : SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE,
                    SoundCategory.NEUTRAL, 0.7F, 1);
            swings++;
            cooldown = SWING_INTERVAL;
            if (!target.isAlive()) {
                finish("Target defeated.");
            } else if (swings == requestedSwings) {
                finish("Attack complete.");
            }
        }
    }

    static boolean canTarget(ServerWorld world, VillagerEntity actor, LivingEntity target) {
        return target != actor && target.isAlive() && target.isAttackable() && !target.isSpectator()
                && actor.squaredDistanceTo(target) <= TARGET_RANGE * TARGET_RANGE
                && (!(target instanceof PlayerEntity player)
                    || (world.getServer().isPvpEnabled() && !player.getAbilities().invulnerable));
    }

    static Status start(ServerWorld world, VillagerEntity actor, LivingEntity target, int swings, int weaponSlot) {
        Attack attack = new Attack(target, swings, weaponSlot);
        WORLDS.computeIfAbsent(world, key -> new HashMap<>()).put(actor.getUuid(), attack);
        return attack.status();
    }

    static Status status(ServerWorld world, UUID actorId) {
        var attacks = WORLDS.get(world);
        Attack attack = attacks == null ? null : attacks.get(actorId);
        return attack == null ? null : attack.status();
    }

    static boolean isActive(ServerWorld world, UUID actorId) {
        var attacks = WORLDS.get(world);
        Attack attack = attacks == null ? null : attacks.get(actorId);
        return attack != null && attack.active;
    }

    static void cancel(ServerWorld world, UUID actorId) {
        var attacks = WORLDS.get(world);
        Attack attack = attacks == null ? null : attacks.get(actorId);
        if (attack != null && attack.active) {
            attack.finish("Attack stopped.");
        }
    }

    public static void tick(ServerWorld world) {
        var attacks = WORLDS.get(world);
        if (attacks == null) {
            return;
        }
        var iterator = attacks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!(world.getEntity(entry.getKey()) instanceof VillagerEntity actor) || !AgentTools.isActor(actor)) {
                iterator.remove();
            } else if (entry.getValue().active) {
                entry.getValue().tick(world, actor);
            }
        }
    }

    public static void clear(ServerWorld world) {
        WORLDS.remove(world);
    }

    private static boolean approach(ServerWorld world, VillagerEntity actor, Vec3d offset) {
        double distance = offset.horizontalLength();
        if (distance < 1.0E-3) {
            return false;
        }
        double multiplier = Math.min(STEP / distance, 1);
        Vec3d delta = new Vec3d(offset.x * multiplier, 0, offset.z * multiplier);
        if (!AgentTools.canMove(world, actor, delta)) {
            return false;
        }
        Vec3d before = actor.getPos();
        actor.move(MovementType.SELF, delta);
        return actor.getPos().subtract(before).horizontalLengthSquared() > 1.0E-6;
    }
}
