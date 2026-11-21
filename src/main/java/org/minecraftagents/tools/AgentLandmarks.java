package org.minecraftagents.tools;

import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Landmarks are read from the loaded world so edits and removals appear immediately. */
final class AgentLandmarks {
    private static final int MAP_LIMIT = 12;
    private static final int HEIGHT_RANGE = 6;

    record Landmark(String kind, String label, String target, AgentTools.Position position) {
        BlockPos pos() {
            return new BlockPos(position.x(), position.y(), position.z());
        }
    }

    record Mob(int entityId, String type, boolean hostile, AgentTools.Position position) {
    }

    private AgentLandmarks() {
    }

    static List<Landmark> map(ServerWorld world, Entity viewer, int radius) {
        BlockPos origin = viewer.getBlockPos();
        return nearby(world, viewer, radius).stream()
                .sorted(Comparator.<Landmark>comparingInt(landmark -> landmark.kind().equals("sign") ? 0 : 1)
                        .thenComparingDouble(landmark -> landmark.pos().getSquaredDistance(origin))
                        .thenComparing(Landmark::label))
                .limit(MAP_LIMIT)
                .toList();
    }

    static List<Mob> mobs(ServerWorld world, Entity viewer, int radius) {
        BlockPos origin = viewer.getBlockPos();
        return world.getEntitiesByClass(MobEntity.class, new Box(origin).expand(radius, HEIGHT_RANGE, radius),
                        mob -> mob.isAlive() && !mob.isInvisible() && inRange(origin, mob.getBlockPos(), radius)
                                && (hostile(mob, viewer) || mob instanceof AnimalEntity)).stream()
                .sorted(Comparator.<MobEntity, Boolean>comparing(mob -> !hostile(mob, viewer))
                        .thenComparingDouble(viewer::squaredDistanceTo).thenComparingInt(Entity::getId))
                .limit(8)
                .map(mob -> new Mob(mob.getId(), Registries.ENTITY_TYPE.getId(mob.getType()).toString(),
                        hostile(mob, viewer), AgentTools.Position.of(mob.getBlockPos())))
                .toList();
    }

    private static boolean hostile(MobEntity mob, Entity viewer) {
        return mob.getType().getSpawnGroup() == SpawnGroup.MONSTER || mob.getTarget() == viewer;
    }

    static Landmark resolve(ServerWorld world, Entity viewer, int radius, String name) {
        String requested = name.strip();
        List<Landmark> landmarks = nearby(world, viewer, radius);
        List<Landmark> matches = landmarks.stream()
                .filter(landmark -> requested.equalsIgnoreCase(landmark.target()))
                .toList();
        if (matches.isEmpty()) {
            matches = landmarks.stream()
                    .filter(landmark -> landmark.target() != null && requested.equalsIgnoreCase(landmark.label()))
                    .toList();
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No loaded player, actor, or sign matches that name within " + radius + " blocks.");
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Several landmarks match. Use the prefixed map target or choose a nearby cell.");
        }
        return matches.get(0);
    }

    private static List<Landmark> nearby(ServerWorld world, Entity viewer, int radius) {
        BlockPos origin = viewer.getBlockPos();
        var landmarks = new ArrayList<Landmark>();
        for (var player : world.getPlayers(player -> player != viewer && player.isAlive()
                && !player.isSpectator() && !player.isInvisible() && inRange(origin, player.getBlockPos(), radius))) {
            add(landmarks, "player", player.getGameProfile().getName(), player.getBlockPos());
        }

        Box area = new Box(origin).expand(radius, HEIGHT_RANGE, radius);
        for (var actor : world.getEntitiesByClass(VillagerEntity.class, area,
                actor -> actor != viewer && AgentTools.isActor(actor) && !actor.isInvisible()
                        && inRange(origin, actor.getBlockPos(), radius))) {
            add(landmarks, "actor", actor.getName().getString(), actor.getBlockPos());
        }

        for (int chunkX = (origin.getX() - radius) >> 4; chunkX <= (origin.getX() + radius) >> 4; chunkX++) {
            for (int chunkZ = (origin.getZ() - radius) >> 4; chunkZ <= (origin.getZ() + radius) >> 4; chunkZ++) {
                var chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    if (!inRange(origin, pos, radius) || entry.getValue().isRemoved()) {
                        continue;
                    }
                    if (entry.getValue() instanceof SignBlockEntity sign) {
                        String label = firstLine(sign.getFrontText());
                        if (label.isEmpty()) {
                            label = firstLine(sign.getBackText());
                        }
                        if (!label.isEmpty()) {
                            add(landmarks, "sign", label, pos);
                        }
                    } else if (entry.getValue() instanceof ChestBlockEntity) {
                        landmarks.add(new Landmark("chest", "Chest", null, AgentTools.Position.of(pos)));
                    }
                }
            }
        }
        return landmarks;
    }

    private static boolean inRange(BlockPos origin, BlockPos pos, int radius) {
        return Math.abs(pos.getX() - origin.getX()) <= radius && Math.abs(pos.getZ() - origin.getZ()) <= radius
                && Math.abs(pos.getY() - origin.getY()) <= HEIGHT_RANGE;
    }

    private static String firstLine(SignText text) {
        for (int line = 0; line < 4; line++) {
            String label = text.getMessage(line, true).getString().replaceAll("\\p{Cntrl}", " ").strip();
            if (!label.isEmpty()) {
                return label.substring(0, label.offsetByCodePoints(0, Math.min(24, label.codePointCount(0, label.length()))));
            }
        }
        return "";
    }

    private static void add(List<Landmark> landmarks, String kind, String label, BlockPos pos) {
        landmarks.add(new Landmark(kind, label, kind + ":" + label, AgentTools.Position.of(pos)));
    }
}
