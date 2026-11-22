package org.minecraftagents.tools;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.List;

import static org.minecraftagents.tools.AgentTools.require;

/** Starter terrain and one shared skill and demonstration library per arena. */
final class AgentArena extends PersistentState {
    private static final String ACTOR_PREFIX = "minecraft_agents.arena.";
    private static final int RADIUS = 10;
    private static final int HEIGHT = 7;
    private final List<Site> sites = new ArrayList<>();

    private record Site(String dimension, BlockPos center) {
    }

    private static AgentArena get(ServerWorld world) {
        return world.getServer().getOverworld().getPersistentStateManager().getOrCreate(
                AgentArena::readNbt, AgentArena::new, "minecraft_agents_arenas");
    }

    static int actorArena(Entity actor) {
        for (String tag : actor.getCommandTags()) {
            if (tag.startsWith(ACTOR_PREFIX)) {
                try {
                    return Integer.parseInt(tag.substring(ACTOR_PREFIX.length()));
                } catch (NumberFormatException ignored) {
                    // Only tags written when creating an arena define a library.
                }
            }
        }
        return 0;
    }

    static int at(ServerWorld world, BlockPos position) {
        var sites = get(world).sites;
        String dimension = world.getRegistryKey().getValue().toString();
        for (int index = sites.size() - 1; index >= 0; index--) {
            var site = sites.get(index);
            var center = site.center();
            if (site.dimension().equals(dimension)
                    && Math.abs(position.getX() - center.getX()) <= RADIUS
                    && Math.abs(position.getZ() - center.getZ()) <= RADIUS
                    && position.getY() > center.getY() && position.getY() <= center.getY() + HEIGHT) {
                return index + 1;
            }
        }
        return 0;
    }

    static String libraryId(ServerWorld world, Entity subject, String base) {
        int arena = subject instanceof ServerPlayerEntity ? at(world, subject.getBlockPos()) : actorArena(subject);
        return arena == 0 ? base : base + "_arena_" + arena;
    }

    static String create(ServerCommandSource source) {
        var world = source.getWorld();
        var center = findSite(world, BlockPos.ofFloored(source.getPosition()));
        var state = get(world);
        int arena = state.sites.size() + 1;
        String suffix = arena == 1 ? "" : Integer.toString(arena);
        var first = actor(world, center.add(-4, 1, -5), "Ada" + suffix, arena);
        var second = actor(world, center.add(4, 1, -5), "Ben" + suffix, arena);
        require(world.spawnEntity(first), "Could not spawn " + first.getName().getString() + ".");
        if (!world.spawnEntity(second)) {
            first.discard();
            throw new AgentTools.ToolFailure("Could not spawn " + second.getName().getString() + ".");
        }

        build(world, center);
        state.sites.add(new Site(world.getRegistryKey().getValue().toString(), center));
        state.markDirty();

        var entrance = center.add(0, 1, -7);
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            player.teleport(world, entrance.getX() + 0.5, entrance.getY(), entrance.getZ() + 0.5, 0, 0);
        }
        return "Arena " + arena + " ready at " + entrance.toShortString() + ". " + first.getName().getString()
                + " and " + second.getName().getString() + " have empty inventories and a fresh shared library.";
    }

    private static BlockPos findSite(ServerWorld world, BlockPos origin) {
        int floor = origin.getY() + 3;
        for (var column : BlockPos.iterate(origin.add(-RADIUS, 0, -RADIUS), origin.add(RADIUS, 0, RADIUS))) {
            require(world.isChunkLoaded(ChunkPos.toLong(column)) && world.getWorldBorder().contains(column),
                    "Choose a loaded area with room inside the world border.");
            floor = Math.max(floor, world.getTopY(Heightmap.Type.MOTION_BLOCKING, column.getX(), column.getZ()) + 2);
        }
        var center = origin.withY(floor);
        var min = center.add(-RADIUS, 0, -RADIUS);
        var max = center.add(RADIUS, HEIGHT, RADIUS);
        require(!world.isOutOfHeightLimit(min) && !world.isOutOfHeightLimit(max), "Choose an area with room below the build limit.");
        for (var pos : BlockPos.iterate(min, max)) {
            require(world.getBlockState(pos).isAir(), "An existing block is in the arena volume.");
        }
        require(world.getOtherEntities(null, new Box(min, max.add(1, 1, 1))).isEmpty(),
                "An entity is in the arena volume.");
        return center;
    }

    private static VillagerEntity actor(ServerWorld world, BlockPos pos, String name, int arena) {
        var actor = EntityType.VILLAGER.create(world);
        require(actor != null, "Could not create an agent.");
        actor.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        actor.setCustomName(Text.literal(name));
        actor.setCustomNameVisible(true);
        actor.setAiDisabled(true);
        actor.setPersistent();
        actor.addCommandTag(AgentTools.ACTOR_TAG);
        actor.addCommandTag(ACTOR_PREFIX + arena);
        actor.getInventory().clear();
        require(world.isSpaceEmpty(actor), "The agent spawn is obstructed.");
        return actor;
    }

    private static void build(ServerWorld world, BlockPos center) {
        var floor = Blocks.GRASS_BLOCK.getDefaultState();
        var wall = Blocks.STONE_BRICKS.getDefaultState();
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                var pos = center.add(x, 0, z);
                boolean edge = Math.abs(x) == RADIUS || Math.abs(z) == RADIUS;
                world.setBlockState(pos, edge ? wall : floor, Block.NOTIFY_ALL);
                if (edge) {
                    world.setBlockState(pos.up(), wall, Block.NOTIFY_ALL);
                    world.setBlockState(pos.up(2), wall, Block.NOTIFY_ALL);
                }
            }
        }
        tree(world, center.add(-5, 1, 5));
        tree(world, center.add(5, 1, 5));
    }

    private static void tree(ServerWorld world, BlockPos trunk) {
        var leaves = Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
        for (var pos : BlockPos.iterate(trunk.add(-1, 3, -1), trunk.add(1, 4, 1))) {
            world.setBlockState(pos, leaves, Block.NOTIFY_ALL);
        }
        for (int y = 0; y <= 3; y++) {
            world.setBlockState(trunk.up(y), Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        }
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        var entries = new NbtList();
        for (var site : sites) {
            var entry = new NbtCompound();
            entry.putString("Dimension", site.dimension());
            entry.putLong("Center", site.center().asLong());
            entries.add(entry);
        }
        nbt.put("Arenas", entries);
        return nbt;
    }

    private static AgentArena readNbt(NbtCompound nbt) {
        var state = new AgentArena();
        var entries = nbt.getList("Arenas", NbtElement.COMPOUND_TYPE);
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.getCompound(index);
            state.sites.add(new Site(entry.getString("Dimension"), BlockPos.fromLong(entry.getLong("Center"))));
        }
        return state;
    }
}
