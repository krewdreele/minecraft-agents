package org.minecraftagents.tools;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Camera-relative perception of blocks and entities. */
final class AgentVision {
    static final double REACH = 4.5;
    private static final double VIEW_DISTANCE = 12;
    private static final int HORIZONTAL_ANGLE = 36;
    private static final int VERTICAL_ANGLE = 24;
    private static final int MAX_ENTITIES = 12;
    private static final double HORIZONTAL_SLOPE = Math.tan(Math.toRadians(HORIZONTAL_ANGLE));
    private static final double VERTICAL_SLOPE = Math.tan(Math.toRadians(VERTICAL_ANGLE));
    private static final Gson GSON = new Gson();

    record Location(double right, double up, double forward) {
    }

    record Block(String block, Location at) {
    }

    private record SeenEntity(String kind, String type, String name, Location at) {
    }

    private record Camera(Vec3d eye, Vec3d heading, Vec3d forward, Vec3d right, Vec3d up) {
        private static Camera of(Vec3d eye, float yaw, float pitch) {
            Vec3d heading = direction(yaw, 0);
            Vec3d forward = direction(yaw, pitch);
            var right = new Vec3d(-heading.z, 0, heading.x);
            return new Camera(eye, heading, forward, right, right.crossProduct(forward));
        }

        private Location location(Vec3d point) {
            var relative = point.subtract(eye);
            return new Location(round(relative.dotProduct(right)), round(relative.y),
                    round(relative.dotProduct(heading)));
        }

        private boolean contains(Vec3d point) {
            var relative = point.subtract(eye);
            double depth = relative.dotProduct(forward);
            return depth > 0 && relative.lengthSquared() <= VIEW_DISTANCE * VIEW_DISTANCE
                    && Math.abs(relative.dotProduct(right)) <= depth * HORIZONTAL_SLOPE
                    && Math.abs(relative.dotProduct(up)) <= depth * VERTICAL_SLOPE;
        }
    }

    private record Scene(BlockHitResult target, Set<BlockPos> blocks) {
    }

    private AgentVision() {
    }

    static JsonObject observe(ServerWorld world, Entity entity, float yaw, float pitch) {
        var scene = sample(world, entity, yaw, pitch);
        var camera = Camera.of(entity.getEyePos(), yaw, pitch);
        var result = new JsonObject();
        var pose = new JsonObject();
        pose.addProperty("yaw", MathHelper.wrapDegrees(yaw));
        pose.addProperty("pitch", pitch);
        pose.addProperty("on_ground", entity.isOnGround());
        result.add("pose", pose);
        result.add("blocks", GSON.toJsonTree(scene.blocks().stream()
                .map(pos -> block(world, camera, pos))
                .toList()));
        result.add("target", GSON.toJsonTree(scene.target().getType() == HitResult.Type.BLOCK
                ? block(world, camera, scene.target().getBlockPos()) : null));
        result.add("entities", GSON.toJsonTree(visibleEntities(world, entity, camera)
                .map(candidate -> describe(candidate, camera.location(candidate.getBoundingBox().getCenter())))
                .toList()));
        return result;
    }

    static JsonObject observe(ServerWorld world, Entity entity) {
        return observe(world, entity, entity.getYaw(), entity.getPitch());
    }

    static boolean sees(ServerWorld world, Entity entity, Predicate<BlockState> matches) {
        return sample(world, entity, entity.getYaw(), entity.getPitch()).blocks().stream()
                .anyMatch(pos -> matches.test(world.getBlockState(pos)));
    }

    static Vec3d directionToVisible(ServerWorld world, Entity entity, Predicate<BlockState> matches) {
        Vec3d eye = entity.getEyePos();
        BlockPos closest = null;
        double distance = Double.MAX_VALUE;
        for (var pos : sample(world, entity, entity.getYaw(), entity.getPitch()).blocks()) {
            double squared = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (squared < distance && matches.test(world.getBlockState(pos))) {
                distance = squared;
                closest = pos;
            }
        }
        AgentTools.require(closest != null, "No matching block is currently visible.");
        return Vec3d.ofCenter(closest).subtract(eye);
    }

    static ItemEntity visibleItem(ServerWorld world, Entity observer, Item item) {
        var camera = Camera.of(observer.getEyePos(), observer.getYaw(), observer.getPitch());
        return visibleEntities(world, observer, camera)
                .filter(entity -> entity instanceof ItemEntity drop && drop.getStack().isOf(item))
                .map(ItemEntity.class::cast)
                .findFirst().orElse(null);
    }

    static BlockHitResult target(ServerWorld world, Entity entity, double range) {
        return ray(world, entity, entity.getYaw(), entity.getPitch(), range);
    }

    private static Scene sample(ServerWorld world, Entity entity, float yaw, float pitch) {
        Set<BlockPos> blocks = new LinkedHashSet<>();
        var target = ray(world, entity, yaw, pitch, VIEW_DISTANCE);
        if (target.getType() == HitResult.Type.BLOCK) {
            blocks.add(target.getBlockPos());
        }
        for (int up = -VERTICAL_ANGLE; up <= VERTICAL_ANGLE; up += 12) {
            for (int side = -HORIZONTAL_ANGLE; side <= HORIZONTAL_ANGLE; side += 12) {
                if (up == 0 && side == 0) {
                    continue;
                }
                var hit = ray(world, entity, yaw + side, MathHelper.clamp(pitch + up, -90, 90), VIEW_DISTANCE);
                if (hit.getType() == HitResult.Type.BLOCK) {
                    blocks.add(hit.getBlockPos());
                }
            }
        }
        return new Scene(target, blocks);
    }

    private static BlockHitResult ray(ServerWorld world, Entity entity, float yaw, float pitch, double range) {
        Vec3d eye = entity.getEyePos();
        return ray(world, entity, eye, eye.add(direction(yaw, pitch).multiply(range)));
    }

    private static BlockHitResult ray(ServerWorld world, Entity entity, Vec3d eye, Vec3d point) {
        return world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, entity));
    }

    private static Stream<Entity> visibleEntities(ServerWorld world, Entity observer, Camera camera) {
        return world.getOtherEntities(observer, observer.getBoundingBox().expand(VIEW_DISTANCE),
                        candidate -> candidate.isAlive() && !candidate.isSpectator() && !candidate.isInvisible()
                                && (candidate instanceof LivingEntity || candidate instanceof ItemEntity)).stream()
                .sorted(Comparator.comparingDouble(candidate -> camera.eye().squaredDistanceTo(candidate.getBoundingBox().getCenter())))
                .filter(candidate -> visible(world, observer, camera, candidate))
                .limit(MAX_ENTITIES);
    }

    private static boolean visible(ServerWorld world, Entity observer, Camera camera, Entity candidate) {
        var box = candidate.getBoundingBox();
        var center = box.getCenter();
        double offset = (box.maxY - box.minY) * 0.4;
        for (var point : List.of(center, center.add(0, offset, 0), center.subtract(0, offset, 0))) {
            if (camera.contains(point) && ray(world, observer, camera.eye(), point).getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    private static SeenEntity describe(Entity entity, Location at) {
        String type = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
        if (entity instanceof ItemEntity item) {
            return new SeenEntity("item", type, Registries.ITEM.getId(item.getStack().getItem()).toString(), at);
        }
        String kind = AgentTools.isActor(entity) ? "actor" : entity instanceof PlayerEntity ? "player"
                : entity instanceof MobEntity ? "mob" : "entity";
        return new SeenEntity(kind, type, entity.getName().getString(), at);
    }

    static Vec3d direction(float yaw, float pitch) {
        double horizontal = Math.cos(Math.toRadians(pitch));
        double angle = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(angle) * horizontal, -Math.sin(Math.toRadians(pitch)), Math.cos(angle) * horizontal);
    }

    private static Block block(ServerWorld world, Camera camera, BlockPos pos) {
        return new Block(Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString(),
                camera.location(Vec3d.ofCenter(pos)));
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
