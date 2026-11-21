package org.minecraftagents.tools;

import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** A local walkability map and shortest cardinal routes across the same cells. */
public final class AgentNavigation {
    static final int MAP_RADIUS = 6;
    static final int MAX_RADIUS = 8;
    static final int NAMED_RADIUS = 16;
    static final String LEGEND = "@ you, . walkable, ^ one block up, v one block down, # blocked, L lava, ? unloaded";
    private static final double STEP = 0.24;
    private static final int STALLED_TICKS = 12;
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentNavigation.class);
    private static final Map<ServerWorld, Map<UUID, Route>> WORLDS = Collections.synchronizedMap(new HashMap<>());

    private AgentNavigation() {
    }

    record MapView(int minX, int minZ, int feetY, List<String> rows, List<AgentTools.Position> destinations,
                   List<AgentLandmarks.Landmark> landmarks, List<AgentLandmarks.Mob> mobs) {
    }

    record Status(AgentTools.Position destination, int steps, int totalSteps, boolean active, boolean completed,
                  String message) {
    }

    private record Grid(BlockPos origin, int radius, int minX, int minZ, BlockPos[][] cells, List<String> rows) {
        BlockPos cell(int x, int z) {
            long column = (long) x - minX;
            long row = (long) z - minZ;
            int size = radius * 2 + 1;
            return column >= 0 && column < size && row >= 0 && row < size
                    ? cells[(int) row][(int) column] : null;
        }
    }

    private static final class Route {
        private final BlockPos destination;
        private final List<BlockPos> path;
        private final String arrival;
        private int index;
        private int stalled;
        private int ticksLeft;
        private boolean active;
        private boolean completed;
        private String message;
        private Supplier<AgentTools.Result> work;

        private Route(BlockPos destination, List<BlockPos> path, String label) {
            this.destination = destination;
            this.path = path;
            this.ticksLeft = 40 + path.size() * 10;
            this.active = !path.isEmpty();
            String where = label == null ? "at " + destination.toShortString()
                    : "near " + label + " (" + destination.toShortString() + ")";
            this.arrival = "Arrived " + where + ".";
            this.message = active ? (label == null ? "Walking to " + destination.toShortString() : "Walking " + where) + "."
                    : label == null ? "Already at the destination." : "Already " + where + ".";
        }

        private Status status() {
            return new Status(AgentTools.Position.of(destination), index, path.size(), active, completed, message);
        }

        private void finish(String reason) {
            active = false;
            message = reason;
            work = null;
        }

        private void arrive() {
            if (work == null) {
                completed = true;
                finish(active ? arrival : message);
            } else {
                AgentTools.Result result = work.get();
                completed = result.ok();
                finish(result.message());
            }
        }

        private void tick(ServerWorld world, VillagerEntity actor) {
            BlockPos next = path.get(index);
            if (!standable(world, actor, next)) {
                finish("A change blocked the route.");
                return;
            }
            if (ticksLeft-- <= 0) {
                finish("The route timed out.");
                return;
            }

            Vec3d before = actor.getPos();
            Vec3d target = Vec3d.ofBottomCenter(next);
            Vec3d offset = target.subtract(before);
            double distance = offset.horizontalLength();
            if (distance <= 0.06 && Math.abs(offset.y) <= 0.06) {
                index++;
                if (index == path.size()) {
                    arrive();
                }
                return;
            }
            boolean descending = offset.y < -0.06;
            if (distance > 0.06 || descending) {
                double fraction = distance > 0.06 ? Math.min(STEP / distance, 1) : 0;
                // Keep ground contact so Minecraft can resolve a full-block step.
                double down = descending ? Math.max(-STEP, offset.y) : -0.02;
                Vec3d delta = new Vec3d(offset.x * fraction, down, offset.z * fraction);
                if (!AgentTools.canMove(world, actor, delta)) {
                    finish("The route left the loaded world.");
                    return;
                }
                if (fraction > 0) {
                    AgentTools.face(actor, (float) Math.toDegrees(Math.atan2(offset.z, offset.x)) - 90);
                }
                float originalStep = actor.getStepHeight();
                if (offset.y > 0.3 && offset.y <= 1.1) {
                    actor.setStepHeight(Math.max(1, originalStep));
                }
                try {
                    actor.move(MovementType.SELF, delta);
                } finally {
                    actor.setStepHeight(originalStep);
                }
            }
            if (actor.getPos().squaredDistanceTo(before) > 1.0E-6) {
                stalled = 0;
            } else if (++stalled >= STALLED_TICKS) {
                finish("The route is blocked.");
            }
        }
    }

    static MapView map(ServerWorld world, Entity actor, int radius) {
        Grid grid = readGrid(world, actor, radius);
        Map<BlockPos, BlockPos> reachable = search(world, actor, grid, cell -> false);
        var suggestions = new ArrayList<AgentTools.Position>(8);
        int near = Math.max(1, radius / 3);
        int far = Math.max(near, radius * 2 / 3);
        for (int distance = near; distance <= far; distance += Math.max(1, far - near)) {
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos point = grid.origin().offset(direction, distance);
                BlockPos cell = grid.cell(point.getX(), point.getZ());
                if (reachable.containsKey(cell)) {
                    suggestions.add(AgentTools.Position.of(cell));
                }
            }
        }
        return new MapView(grid.minX(), grid.minZ(), grid.origin().getY(), grid.rows(), List.copyOf(suggestions),
                AgentLandmarks.map(world, actor, radius), AgentLandmarks.mobs(world, actor, radius));
    }

    static Status status(ServerWorld world, UUID actorId) {
        Route route = route(world, actorId);
        return route == null ? null : route.status();
    }

    static boolean isActive(ServerWorld world, UUID actorId) {
        Route route = route(world, actorId);
        return route != null && route.active;
    }

    static Status start(ServerWorld world, VillagerEntity actor, int x, int z) {
        BlockPos origin = actor.getBlockPos();
        if (Math.abs((long) x - origin.getX()) > MAX_RADIUS || Math.abs((long) z - origin.getZ()) > MAX_RADIUS) {
            throw new IllegalArgumentException("Choose a destination within eight blocks on the map.");
        }
        Grid grid = readGrid(world, actor, MAX_RADIUS);
        BlockPos destination = grid.cell(x, z);
        if (destination == null) {
            throw new IllegalArgumentException("The destination is blocked, unsafe, or unloaded. Choose a walkable map cell.");
        }
        Map<BlockPos, BlockPos> previous = search(world, actor, grid, destination::equals);
        if (!previous.containsKey(destination)) {
            throw new IllegalArgumentException("There is no walkable route to that cell within the local map.");
        }
        return registerRoute(world, actor, destination, previous, null);
    }

    static Status startNear(ServerWorld world, VillagerEntity actor, AgentLandmarks.Landmark landmark) {
        Grid grid = readGrid(world, actor, NAMED_RADIUS);
        BlockPos target = landmark.pos();
        Predicate<BlockPos> near = cell -> canSeeLandmark(world, actor, cell, target);
        Map<BlockPos, BlockPos> previous = search(world, actor, grid, near);
        BlockPos destination = previous.keySet().stream().filter(near).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No walkable cell leads near " + landmark.label() + "."));
        return registerRoute(world, actor, destination, previous, landmark.label());
    }

    static Status startGather(ServerWorld world, VillagerEntity actor, List<BlockPos> targets,
                              Function<BlockPos, AgentTools.Result> gather) {
        Grid grid = readGrid(world, actor, MAX_RADIUS);
        Map<BlockPos, BlockPos> previous = search(world, actor, grid, cell -> false);
        for (BlockPos cell : previous.keySet()) {
            Vec3d eye = cell.equals(grid.origin()) ? actor.getEyePos()
                    : Vec3d.ofBottomCenter(cell).add(0, actor.getEyeY() - actor.getY(), 0);
            for (BlockPos target : targets) {
                if (!target.equals(cell.down()) && AgentTools.canReachBlock(world, actor, eye, target)) {
                    Route route = createRoute(world, actor, cell, previous, null);
                    route.work = () -> gather.apply(target);
                    if (route.active) {
                        route.message = "Walking into reach of the resource at " + target.toShortString() + ".";
                    } else {
                        route.arrive();
                    }
                    return route.status();
                }
            }
        }
        throw new IllegalArgumentException("No walkable route brings a matching block into reach.");
    }

    static Status startRetreat(ServerWorld world, VillagerEntity actor) {
        var threats = AgentLandmarks.mobs(world, actor, MAX_RADIUS).stream()
                .filter(AgentLandmarks.Mob::hostile).map(AgentLandmarks.Mob::position).toList();
        if (threats.isEmpty()) {
            throw new IllegalArgumentException("There are no hostile mobs nearby to retreat from.");
        }
        Grid grid = readGrid(world, actor, MAX_RADIUS);
        BlockPos origin = grid.origin();
        Map<BlockPos, BlockPos> previous = search(world, actor, grid, cell -> false,
                cell -> threats.stream().allMatch(threat ->
                        horizontalDistanceSquared(cell, threat) >= Math.min(9, horizontalDistanceSquared(origin, threat))));
        int initialDistance = nearestThreat(origin, threats);
        BlockPos destination = previous.keySet().stream().filter(cell -> nearestThreat(cell, threats) > initialDistance)
                .max(Comparator.comparingInt(cell -> nearestThreat(cell, threats)))
                .orElseThrow(() -> new IllegalArgumentException("No local walkable route increases distance from the hostiles."));
        Route route = createRoute(world, actor, destination, previous, null);
        route.message = "Retreating from " + threats.size() + " hostile mob(s) to " + destination.toShortString() + ".";
        return route.status();
    }

    private static int nearestThreat(BlockPos cell, List<AgentTools.Position> threats) {
        int nearest = Integer.MAX_VALUE;
        for (var threat : threats) {
            nearest = Math.min(nearest, horizontalDistanceSquared(cell, threat));
        }
        return nearest;
    }

    private static int horizontalDistanceSquared(BlockPos cell, AgentTools.Position threat) {
        int dx = cell.getX() - threat.x();
        int dz = cell.getZ() - threat.z();
        return dx * dx + dz * dz;
    }

    private static boolean canSeeLandmark(ServerWorld world, VillagerEntity actor, BlockPos cell, BlockPos target) {
        int dx = cell.getX() - target.getX();
        int dz = cell.getZ() - target.getZ();
        int horizontal = dx * dx + dz * dz;
        if (horizontal == 0 || horizontal > 4 || Math.abs(cell.getY() - target.getY()) > 1) {
            return false;
        }
        Vec3d eye = Vec3d.ofBottomCenter(cell).add(0, actor.getEyeY() - actor.getY(), 0);
        var hit = world.raycast(new RaycastContext(eye, Vec3d.ofCenter(target), RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, actor));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    private static Status registerRoute(ServerWorld world, VillagerEntity actor, BlockPos destination,
                                        Map<BlockPos, BlockPos> previous, String label) {
        Route route = createRoute(world, actor, destination, previous, label);
        if (!route.active) {
            route.arrive();
        }
        return route.status();
    }

    private static Route createRoute(ServerWorld world, VillagerEntity actor, BlockPos destination,
                                     Map<BlockPos, BlockPos> previous, String label) {
        var path = new ArrayList<BlockPos>();
        for (BlockPos node = destination; !node.equals(actor.getBlockPos()); node = previous.get(node)) {
            path.add(node);
        }
        Collections.reverse(path);
        var route = new Route(destination, path, label);
        WORLDS.computeIfAbsent(world, key -> new HashMap<>()).put(actor.getUuid(), route);
        return route;
    }

    static void cancel(ServerWorld world, UUID actorId) {
        Route route = route(world, actorId);
        if (route != null && route.active) {
            route.finish("Route stopped.");
        }
    }

    private static Route route(ServerWorld world, UUID actorId) {
        var routes = WORLDS.get(world);
        return routes == null ? null : routes.get(actorId);
    }

    public static void tick(ServerWorld world) {
        var routes = WORLDS.get(world);
        if (routes == null) {
            return;
        }
        var iterator = routes.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!(world.getEntity(entry.getKey()) instanceof VillagerEntity actor) || !AgentTools.isActor(actor)) {
                iterator.remove();
            } else if (entry.getValue().active) {
                Route route = entry.getValue();
                route.tick(world, actor);
                if (!route.active) {
                    LOGGER.info("[Agent route] {} {} Steps: {}/{}.", actor.getName().getString(),
                            route.message, route.index, route.path.size());
                }
            }
        }
    }

    public static void clear(ServerWorld world) {
        WORLDS.remove(world);
    }

    private static Grid readGrid(ServerWorld world, Entity actor, int radius) {
        BlockPos origin = actor.getBlockPos();
        int size = radius * 2 + 1;
        int minX = origin.getX() - radius;
        int minZ = origin.getZ() - radius;
        var cells = new BlockPos[size][size];
        var rows = new ArrayList<String>(size);
        for (int row = 0; row < size; row++) {
            var symbols = new StringBuilder(size);
            for (int column = 0; column < size; column++) {
                BlockPos base = new BlockPos(minX + column, origin.getY(), minZ + row);
                if (base.equals(origin)) {
                    cells[row][column] = origin;
                    symbols.append('@');
                } else if (!world.isChunkLoaded(ChunkPos.toLong(base)) || !world.getWorldBorder().contains(base)) {
                    symbols.append('?');
                } else {
                    BlockPos cell = floor(world, actor, base);
                    cells[row][column] = cell;
                    symbols.append(cell == null ? (lavaInLayer(world, base) ? 'L' : '#') : switch (cell.getY() - base.getY()) {
                        case 1 -> '^';
                        case -1 -> 'v';
                        default -> '.';
                    });
                }
            }
            rows.add(symbols.toString());
        }
        return new Grid(origin, radius, minX, minZ, cells, List.copyOf(rows));
    }

    private static boolean lavaInLayer(ServerWorld world, BlockPos base) {
        for (int dy = -1; dy <= 2; dy++) {
            if (world.getFluidState(base.up(dy)).isIn(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos floor(ServerWorld world, Entity actor, BlockPos base) {
        if (standable(world, actor, base)) {
            return base;
        }
        if (standable(world, actor, base.up())) {
            return base.up();
        }
        return standable(world, actor, base.down()) ? base.down() : null;
    }

    private static boolean standable(ServerWorld world, Entity actor, BlockPos feet) {
        BlockPos support = feet.down();
        if (world.isOutOfHeightLimit(support) || world.isOutOfHeightLimit(feet.up())
                || !world.isChunkLoaded(ChunkPos.toLong(feet))) {
            return false;
        }
        var below = world.getBlockState(support);
        var atFeet = world.getBlockState(feet);
        return below.isSideSolidFullSquare(world, support, Direction.UP) && !below.isOf(Blocks.MAGMA_BLOCK)
                && !atFeet.isIn(BlockTags.FIRE) && !atFeet.isOf(Blocks.POWDER_SNOW)
                && world.getFluidState(feet).isEmpty() && world.getFluidState(feet.up()).isEmpty()
                && clearAt(world, actor, feet);
    }

    private static boolean clearAt(ServerWorld world, Entity actor, BlockPos feet) {
        Box box = actor.getBoundingBox().offset(Vec3d.ofBottomCenter(feet).subtract(actor.getPos()));
        return world.getWorldBorder().contains(box) && world.isSpaceEmpty(actor, box);
    }

    private static Map<BlockPos, BlockPos> search(ServerWorld world, Entity actor, Grid grid, Predicate<BlockPos> reached) {
        return search(world, actor, grid, reached, cell -> true);
    }

    private static Map<BlockPos, BlockPos> search(ServerWorld world, Entity actor, Grid grid, Predicate<BlockPos> reached,
                                                   Predicate<BlockPos> allowed) {
        var queue = new ArrayDeque<BlockPos>();
        var previous = new LinkedHashMap<BlockPos, BlockPos>();
        queue.add(grid.origin());
        previous.put(grid.origin(), grid.origin());
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            if (reached.test(current)) {
                break;
            }
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos next = grid.cell(current.getX() + direction.getOffsetX(), current.getZ() + direction.getOffsetZ());
                if (next != null && !previous.containsKey(next) && allowed.test(next)
                        && canCross(world, actor, current, next)) {
                    previous.put(next, current);
                    queue.addLast(next);
                }
            }
        }
        return previous;
    }

    private static boolean canCross(ServerWorld world, Entity actor, BlockPos from, BlockPos to) {
        return switch (to.getY() - from.getY()) {
            case 0 -> true;
            case 1 -> clearAt(world, actor, from.up());
            case -1 -> clearAt(world, actor, to.up());
            default -> false;
        };
    }
}
