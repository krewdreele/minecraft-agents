package org.minecraftagents.tools;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AutomaticItemPlacementContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SignItem;
import net.minecraft.item.SwordItem;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.GameRules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Synchronous tools for a loaded actor. Schedule calls on Minecraft's server thread. */
public final class AgentTools {
    static final String ACTOR_TAG = "minecraft_agents_actor";
    private static final double REACH = 4.5;
    private static final double TALK_RANGE = 8;
    private static final int GATHER_TARGET_LIMIT = 64;
    private static final int NO_GATHER_TOOL = -2;
    private static final Gson GSON = new Gson();
    private static final Set<String> MILESTONES = Set.of("craft", "eat", "place_block", "place_sign", "break_block",
            "pickup", "take_from_chest", "put_in_chest", "write_knowledge");

    public record Parameter(String name, String type, String description, boolean required) {
    }

    public record Descriptor(String name, String description, List<Parameter> parameters) {
    }

    public record Result(boolean ok, String message, JsonElement data) {
    }

    private record Tool(Descriptor descriptor, BiFunction<Context, JsonObject, Result> action) {
    }

    record Position(int x, int y, int z) {
        static Position of(BlockPos pos) {
            return new Position(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    private record SeenBlock(String block, Position position) {
    }

    private record SeenEntity(int id, UUID uuid, String type, String kind, String name, Position position,
                              int count, Float health) {
    }

    private record Context(ServerWorld world, VillagerEntity actor) {
        Inventory inventory() {
            return actor.getInventory();
        }

        Vec3d eye() {
            return actor.getEyePos();
        }
    }

    private static final Parameter X = integer("x", "Block X coordinate.");
    private static final Parameter Y = integer("y", "Block Y coordinate.");
    private static final Parameter Z = integer("z", "Block Z coordinate.");
    private static final Parameter SLOT = integer("slot", "Source slot number.");
    private static final Parameter COUNT = integer("count", "Maximum number of items to move.");
    private static final Parameter TOOL_SLOT = new Parameter("tool_slot", "integer", "Carried mining tool slot.", false);

    private static final List<Tool> TOOLS = List.of(
            tool("inventory", "List carried items by slot.", (ctx, args) ->
                    ok("Carried items.", AgentInventory.slots(ctx.inventory()))),
            tool("eat", "Consume one carried food item to recover health.", AgentTools::eat,
                    integer("slot", "Inventory slot containing food.")),
            tool("look", "See nearby blocks, entities, dropped items, and action status.", AgentTools::look,
                    new Parameter("radius", "integer", "Scan 1 to 6 blocks. Default 4.", false),
                    new Parameter("block", "string", "Find visible positions of one exact block ID.", false)),
            tool("map", "Get a north-up walkability grid, reachable destinations, and nearby landmarks.", AgentTools::map,
                    new Parameter("radius", "integer", "Map 1 to 8 blocks in each direction. Default 6.", false)),
            tool("go_to", "Walk to a map cell or near a named sign, player, or actor.", AgentTools::goTo,
                    new Parameter("target", "string", "Map target such as sign:Farm or player:Alex. Use by itself.", false),
                    new Parameter("x", "integer", "Absolute map X coordinate. Pair with z.", false),
                    new Parameter("z", "integer", "Absolute map Z coordinate. Pair with x.", false)),
            tool("move", "Step up to one block in a cardinal direction.", AgentTools::move,
                    string("direction", "north, south, east, or west.")),
            tool("retreat", "Walk to a reachable spot farther from nearby hostile mobs.", AgentTools::retreat),
            tool("attack", "Approach a nearby living target and swing in melee range.", AgentTools::attack,
                    integer("entity_id", "Numeric entity ID from map or look, within six blocks."),
                    new Parameter("hits", "integer", "Swing attempts from 1 to 5. Default 1.", false),
                    new Parameter("weapon_slot", "integer", "Inventory slot of a carried sword. Omit for unarmed.", false)),
            tool("place_block", "Place a carried block at reachable coordinates.", AgentTools::place,
                    string("block", "Block item ID, for example minecraft:oak_planks."), X, Y, Z),
            tool("craft", "Craft an item from carried ingredients. A reachable crafting table enables 3x3 recipes.",
                    AgentTools::craft, string("item", "Output item ID, for example minecraft:oak_planks."),
                    new Parameter("batches", "integer", "Recipe repetitions from 1 to 16. Default 1.", false)),
            tool("recipe_book", "Read starter recipes, recipes you can craft now, or your saved crafting history.",
                    AgentTools::recipeBook,
                    new Parameter("section", "string", "starter, craftable, or crafted. Default starter.", false),
                    new Parameter("offset", "integer", "Next offset from a prior page. Default 0.", false)),
            tool("place_sign", "Place a carried sign with a short landmark label visible on nearby maps.",
                    AgentTools::placeSign, X, Y, Z, string("label", "Up to 15 characters, for example Farm.")),
            tool("break_block", "Break a reachable block by hand or with a carried tool.", AgentTools::breakBlock,
                    X, Y, Z, TOOL_SLOT),
            tool("gather", "Approach a nearby matching block, mine it with a carried tool or by hand, and collect its drops.",
                    AgentTools::gather, string("block", "Exact block ID or tag, for example minecraft:oak_log or #minecraft:logs."),
                    TOOL_SLOT),
            tool("pickup", "Pick up a reachable item entity from a look result.", AgentTools::pickup,
                    integer("entity_id", "Numeric entity ID from look.")),
            tool("open_chest", "Inspect a reachable, unlocked chest's slots.", AgentTools::openChest, X, Y, Z),
            tool("take_from_chest", "Move items from a chest slot into inventory.", (ctx, args) ->
                    transfer(ctx, args, true), X, Y, Z, SLOT, COUNT),
            tool("put_in_chest", "Move carried items from a slot into a chest.", (ctx, args) ->
                    transfer(ctx, args, false), X, Y, Z, SLOT, COUNT),
            tool("talk", "Send a short message to a nearby actor.", AgentTools::talk,
                    string("target", "Actor UUID from look or a unique nearby name."),
                    string("message", "Message of 1 to 160 characters.")),
            tool("read_messages", "Read the most recent messages sent to this actor.", (ctx, args) ->
                    ok("Inbox.", AgentMailbox.get(ctx.world()).inbox(ctx.actor().getUuid()))),
            tool("recall", "Search your saved milestones and both sides of past conversations.", AgentTools::recall,
                    new Parameter("query", "string", "Optional phrase to find in memory.", false),
                    new Parameter("partner", "string", "Optional actor name to filter conversations.", false),
                    new Parameter("offset", "integer", "Next offset from a prior page. Default 0.", false)),
            tool("write_knowledge", "Save a useful observed fact in the shared world journal.", AgentTools::writeKnowledge,
                    string("topic", "Short topic, up to 40 characters."),
                    string("note", "One line of knowledge, up to 180 characters.")),
            tool("read_knowledge", "Read recent notes from the shared world journal.", AgentTools::readKnowledge,
                    optional("query", "string", "Optional keyword in the topic or note.")),
            tool("save_skill", "Save or revise a reusable program of existing tools. Only you can revise your skills.",
                    (ctx, args) -> AgentSkills.save(ctx.world(), ctx.actor(), args),
                    string("name", "Short snake_case name."), string("description", "What the program does."),
                    new Parameter("steps", "array", "1-8 steps {tool,args?,repeat?,if?,assert?}. Args can use $parameter strings. if checks the previous result, assert checks this result. Both use {path,equals}, e.g. path data.block. repeat is 1-4.", true)),
            tool("find_skills", "Find shared skills, or read one program by name.",
                    (ctx, args) -> AgentSkills.find(ctx.world(), args),
                    optional("query", "string", "Keyword in skill names or descriptions."),
                    optional("name", "string", "Exact name for the program and parameters."),
                    optional("version", "integer", "Version to read. Default latest.")),
            tool("run_skill", "Run someone's saved program with its named arguments and get the completed result.",
                    (ctx, args) -> AgentSkills.start(ctx.world(), ctx.actor(), args),
                    string("name", "Saved skill name."), optional("version", "integer", "Default latest."),
                    optional("args", "object", "Values for the program's named parameters.")),
            tool("skill_status", "Read your current or last skill run, including its last result.",
                    (ctx, args) -> AgentSkills.status(ctx.world(), ctx.actor().getUuid())));

    private static final Set<String> PRIMITIVES = Set.of("inventory", "eat", "look", "map", "go_to", "move",
            "retreat", "attack", "place_block", "craft", "place_sign", "break_block", "gather", "pickup",
            "open_chest", "take_from_chest", "put_in_chest");

    private AgentTools() {
    }

    public static List<Descriptor> descriptors() {
        return TOOLS.stream().map(Tool::descriptor).toList();
    }

    public static Result call(ServerWorld world, UUID actorId, String name, JsonObject args) {
        return dispatch(world, actorId, name, args, false);
    }

    static Result callStep(ServerWorld world, UUID actorId, String name, JsonObject args) {
        return dispatch(world, actorId, name, args, true);
    }

    private static Result dispatch(ServerWorld world, UUID actorId, String name, JsonObject args,
                                   boolean skillStep) {
        if (!world.getServer().isOnThread()) {
            throw new IllegalStateException("Call agent tools on the Minecraft server thread.");
        }
        if (!(world.getEntity(actorId) instanceof VillagerEntity actor) || !isActor(actor)) {
            return error("The actor is unavailable in this world.");
        }
        Tool tool = TOOLS.stream().filter(entry -> entry.descriptor().name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            return error("Unknown tool " + name + ".");
        }
        try {
            if (!skillStep && PRIMITIVES.contains(name)) {
                require(!AgentSkills.isActive(world, actorId), "Wait for the skill to finish.");
            }
            validate(tool.descriptor(), args);
            var result = tool.action().apply(new Context(world, actor), args);
            if (result.ok() && MILESTONES.contains(name)) {
                String coordinates = args.has("x") && args.has("y") && args.has("z")
                        ? " At (" + number(args, "x") + ", " + number(args, "y") + ", " + number(args, "z") + ")."
                        : "";
                AgentMemory.get(world).rememberAction(actorId, name, result.message() + coordinates, world.getTime());
            }
            return result;
        } catch (ToolFailure failure) {
            return error(failure.getMessage());
        }
    }

    static Descriptor primitive(String name) {
        require(PRIMITIVES.contains(name), "A skill step must use an existing world tool. Invalid tool " + name + ".");
        return TOOLS.stream().map(Tool::descriptor).filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    static boolean pending(String tool, Result result) {
        return result.ok() && switch (tool) {
            case "attack", "go_to", "gather", "retreat", "run_skill" ->
                    result.data().getAsJsonObject().get("active").getAsBoolean();
            default -> false;
        };
    }

    static boolean busy(ServerWorld world, UUID actorId) {
        return AgentNavigation.isActive(world, actorId) || AgentCombat.isActive(world, actorId);
    }

    static Result completed(ServerWorld world, UUID actorId, String tool) {
        if (tool.equals("attack")) {
            var attack = AgentCombat.status(world, actorId);
            return attack == null ? error("The attack ended.") : new Result(attack.hits() > 0,
                    attack.message() + " Hits: " + attack.hits() + ".", GSON.toJsonTree(attack));
        }
        var route = AgentNavigation.status(world, actorId);
        return route == null ? error("The route ended.") : new Result(route.completed(), route.message(),
                GSON.toJsonTree(route));
    }

    static void cancelAction(ServerWorld world, UUID actorId) {
        AgentNavigation.cancel(world, actorId);
        AgentCombat.cancel(world, actorId);
    }

    static boolean isActor(Entity entity) {
        return entity instanceof VillagerEntity && entity.isAlive() && entity.getCommandTags().contains(ACTOR_TAG);
    }

    static JsonObject mapSnapshot(ServerWorld world, Entity viewer, int radius) {
        return GSON.toJsonTree(AgentNavigation.map(world, viewer, radius)).getAsJsonObject();
    }

    static JsonObject localObservation(ServerWorld world, VillagerEntity actor) {
        return look(new Context(world, actor), 6, null, true).data().getAsJsonObject();
    }

    static JsonElement knowledgeSnapshot(ServerWorld world) {
        return GSON.toJsonTree(AgentKnowledge.get(world).read("", 2));
    }

    static JsonElement craftingSnapshot(ServerWorld world, VillagerEntity actor) {
        return GSON.toJsonTree(AgentRecipeBook.get(world).preview(world, actor, hasCraftingTable(new Context(world, actor))));
    }

    static JsonElement memorySnapshot(ServerWorld world, UUID actorId, String goal) {
        return GSON.toJsonTree(AgentMemory.get(world).view(actorId, goal));
    }

    private static Result recall(Context ctx, JsonObject args) {
        String query = args.has("query") ? text(args, "query").strip() : "";
        String partner = args.has("partner") ? text(args, "partner").strip() : "";
        int offset = args.has("offset") ? number(args, "offset") : 0;
        require(query.length() <= 160 && query.chars().noneMatch(Character::isISOControl)
                && partner.length() <= 24 && partner.chars().noneMatch(Character::isISOControl) && offset >= 0,
                "Use a short query, a valid actor name, and an offset of zero or more.");
        var page = AgentMemory.get(ctx.world()).recall(ctx.actor().getUuid(), query, partner, offset);
        return ok(page.entries().isEmpty() ? "No matching memories."
                : "Recalled " + page.entries().size() + " memories.", page);
    }

    private static Result eat(Context ctx, JsonObject args) {
        int slot = number(args, "slot");
        Inventory inv = ctx.inventory();
        require(slot >= 0 && slot < inv.size(), "Choose a carried food slot.");
        ItemStack stack = inv.getStack(slot);
        var food = stack.getItem().getFoodComponent();
        require(!stack.isEmpty() && food != null, "That slot does not contain food.");
        float before = ctx.actor().getHealth();
        require(before < ctx.actor().getMaxHealth() || food.isAlwaysEdible(), "Health is already full.");

        String item = Registries.ITEM.getId(stack.getItem()).toString();
        // Finish using a single portion so Minecraft can apply effects and return any container.
        ItemStack remainder = stack.copyWithCount(1).finishUsing(ctx.world(), ctx.actor());
        inv.removeStack(slot, 1);
        if (!remainder.isEmpty()) {
            int moved = AgentInventory.insert(inv, remainder, remainder.getCount());
            if (moved < remainder.getCount()) {
                ctx.actor().dropStack(remainder.copyWithCount(remainder.getCount() - moved));
            }
        }
        inv.markDirty();
        ctx.actor().heal(food.getHunger());
        float after = ctx.actor().getHealth();
        return ok("Ate " + item + ". Health rose by " + (after - before) + " to " + after + ".",
                Map.of("item", item, "healed", after - before, "health", after));
    }

    private static Result writeKnowledge(Context ctx, JsonObject args) {
        String topic = text(args, "topic").strip();
        String note = text(args, "note").strip();
        require(!topic.isEmpty() && topic.length() <= AgentKnowledge.MAX_TOPIC_LENGTH
                && topic.chars().noneMatch(Character::isISOControl), "Use a topic of 1 to 40 characters on one line.");
        require(!note.isEmpty() && note.length() <= AgentKnowledge.MAX_NOTE_LENGTH
                && note.chars().noneMatch(Character::isISOControl), "Use a note of 1 to 180 characters on one line.");
        return ok("Recorded " + topic + ".", AgentKnowledge.get(ctx.world()).write(ctx.world(), ctx.actor(), topic, note));
    }

    private static Result readKnowledge(Context ctx, JsonObject args) {
        String query = args.has("query") ? text(args, "query").strip() : "";
        require(query.length() <= AgentKnowledge.MAX_TOPIC_LENGTH && query.chars().noneMatch(Character::isISOControl),
                "Use a search of up to 40 characters on one line.");
        var notes = AgentKnowledge.get(ctx.world()).read(query, AgentKnowledge.READ_LIMIT);
        return ok(notes.isEmpty() ? "No matching notes." : "Found " + notes.size() + " notes.", notes);
    }

    private static Result look(Context ctx, JsonObject args) {
        int radius = args.has("radius") ? number(args, "radius") : 4;
        require(radius >= 1 && radius <= 6, "Use a radius between 1 and 6.");
        Block filter = null;
        if (args.has("block")) {
            Identifier id = Identifier.tryParse(text(args, "block"));
            require(id != null && Registries.BLOCK.containsId(id), "Use a known block ID.");
            filter = Registries.BLOCK.get(id);
        }
        return look(ctx, radius, filter, false);
    }

    private static Result look(Context ctx, int radius, Block filter, boolean distinctTypes) {
        BlockPos center = BlockPos.ofFloored(ctx.eye());
        var positions = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -radius, -radius), center.add(radius, radius, radius))) {
            if (!within(ctx, pos, radius) || !loaded(ctx, pos)) {
                continue;
            }
            BlockState state = ctx.world().getBlockState(pos);
            if (filter == null ? !state.isAir() : state.isOf(filter)) {
                positions.add(pos.toImmutable());
            }
        }
        positions.sort(Comparator.comparingDouble(pos -> Vec3d.ofCenter(pos).squaredDistanceTo(ctx.eye())));
        var blocks = new ArrayList<SeenBlock>();
        var seenTypes = new HashSet<Block>();
        int limit = distinctTypes ? 6 : 32;
        for (BlockPos pos : positions) {
            Block block = ctx.world().getBlockState(pos).getBlock();
            if ((distinctTypes && seenTypes.contains(block)) || !visible(ctx, Vec3d.ofCenter(pos), pos)) {
                continue;
            }
            blocks.add(new SeenBlock(Registries.BLOCK.getId(block).toString(), Position.of(pos)));
            seenTypes.add(block);
            if (blocks.size() == limit) {
                break;
            }
        }
        List<SeenEntity> entities = ctx.world().getOtherEntities(ctx.actor(), ctx.actor().getBoundingBox().expand(radius),
                        entity -> entity.isAlive() && (entity instanceof ItemEntity || entity instanceof LivingEntity))
                .stream().filter(entity -> ctx.actor().squaredDistanceTo(entity) <= radius * radius)
                .filter(entity -> visible(ctx, entity.getPos().add(0, entity.getHeight() / 2, 0), null))
                .sorted(Comparator.comparingDouble(ctx.actor()::squaredDistanceTo)).limit(24)
                .map(AgentTools::describeEntity).toList();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("position", Position.of(ctx.actor().getBlockPos()));
        view.put("blocks", blocks);
        view.put("entities", entities);
        var attack = AgentCombat.status(ctx.world(), ctx.actor().getUuid());
        if (attack != null) {
            view.put("attack", attack);
        }
        var route = AgentNavigation.status(ctx.world(), ctx.actor().getUuid());
        if (route != null) {
            view.put("route", route);
        }
        return ok(filter == null ? "Nearby view."
                : "Visible " + Registries.BLOCK.getId(filter) + " matches shown: " + blocks.size() + ".", view);
    }

    private static Result map(Context ctx, JsonObject args) {
        int radius = args.has("radius") ? number(args, "radius") : AgentNavigation.MAP_RADIUS;
        require(radius >= 1 && radius <= AgentNavigation.MAX_RADIUS, "Use a map radius between 1 and 8.");
        return new Result(true, "Walkability map. " + AgentNavigation.LEGEND + ".",
                mapSnapshot(ctx.world(), ctx.actor(), radius));
    }

    private static Result goTo(Context ctx, JsonObject args) {
        requireIdle(ctx);
        boolean named = args.has("target");
        require(named ? !args.has("x") && !args.has("z") : args.has("x") && args.has("z"),
                "Supply either target or both x and z.");
        try {
            var route = named ? AgentNavigation.startNear(ctx.world(), ctx.actor(),
                    AgentLandmarks.resolve(ctx.world(), ctx.actor(), AgentNavigation.NAMED_RADIUS, text(args, "target")))
                    : AgentNavigation.start(ctx.world(), ctx.actor(), number(args, "x"), number(args, "z"));
            return ok(route.message(), route);
        } catch (IllegalArgumentException invalid) {
            return error(invalid.getMessage());
        }
    }

    private static Result retreat(Context ctx, JsonObject args) {
        requireIdle(ctx);
        try {
            var route = AgentNavigation.startRetreat(ctx.world(), ctx.actor());
            return ok(route.message(), route);
        } catch (IllegalArgumentException invalid) {
            return error(invalid.getMessage());
        }
    }

    private static SeenEntity describeEntity(Entity entity) {
        String kind = entity instanceof ItemEntity ? "item"
                : isActor(entity) ? "actor"
                : entity instanceof ServerPlayerEntity ? "player"
                : entity instanceof AnimalEntity ? "animal"
                : entity instanceof MobEntity ? "mob" : "living";
        ItemStack stack = entity instanceof ItemEntity item ? item.getStack() : ItemStack.EMPTY;
        String name = stack.isEmpty() ? entity.getName().getString() : Registries.ITEM.getId(stack.getItem()).toString();
        Float health = entity instanceof LivingEntity living ? living.getHealth() : null;
        return new SeenEntity(entity.getId(), entity.getUuid(), Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                kind, name, Position.of(entity.getBlockPos()), stack.getCount(), health);
    }

    private static Result move(Context ctx, JsonObject args) {
        require(!AgentCombat.isActive(ctx.world(), ctx.actor().getUuid()), "An attack is in progress.");
        require(!AgentNavigation.isActive(ctx.world(), ctx.actor().getUuid()), "A route is in progress.");
        Direction direction = Direction.byName(text(args, "direction"));
        require(direction != null && direction.getAxis().isHorizontal(), "Choose north, south, east, or west.");
        Vec3d before = ctx.actor().getPos();
        Vec3d delta = new Vec3d(direction.getOffsetX(), 0, direction.getOffsetZ());
        require(canMove(ctx.world(), ctx.actor(), delta),
                "The destination is outside the loaded world.");
        face(ctx.actor(), direction.asRotation());
        ctx.actor().move(MovementType.SELF, delta);
        double distance = Math.sqrt(before.squaredDistanceTo(ctx.actor().getPos()));
        require(distance > 0.01, "The actor's path is blocked.");
        return ok("Moved " + direction.getName() + ".", Map.of(
                "position", Position.of(ctx.actor().getBlockPos()), "distance", distance));
    }

    private static Result attack(Context ctx, JsonObject args) {
        int hits = args.has("hits") ? number(args, "hits") : 1;
        require(hits >= 1 && hits <= AgentCombat.MAX_SWINGS, "Choose between 1 and 5 swings.");
        int weapon = -1;
        if (args.has("weapon_slot")) {
            weapon = number(args, "weapon_slot");
            require(weapon >= 0 && weapon < ctx.inventory().size()
                    && ctx.inventory().getStack(weapon).getItem() instanceof SwordItem, "Choose the slot of a carried sword.");
        }
        require(!AgentNavigation.isActive(ctx.world(), ctx.actor().getUuid()), "A route is in progress.");
        require(!AgentCombat.isActive(ctx.world(), ctx.actor().getUuid()), "An attack is already in progress.");
        Entity entity = ctx.world().getEntityById(number(args, "entity_id"));
        require(entity instanceof LivingEntity target && AgentCombat.canTarget(ctx.world(), ctx.actor(), target),
                "Choose an attackable living target within six blocks.");
        LivingEntity target = (LivingEntity) entity;
        return ok("Attack started against " + target.getName().getString() + ".",
                AgentCombat.start(ctx.world(), ctx.actor(), target, hits, weapon));
    }

    static boolean canMove(ServerWorld world, VillagerEntity actor, Vec3d delta) {
        BlockPos destination = BlockPos.ofFloored(actor.getPos().add(delta));
        return !world.isOutOfHeightLimit(destination) && world.isChunkLoaded(ChunkPos.toLong(destination))
                && world.getWorldBorder().contains(actor.getBoundingBox().offset(delta));
    }

    static void face(VillagerEntity actor, float yaw) {
        actor.setYaw(yaw);
        actor.setHeadYaw(yaw);
        actor.setBodyYaw(yaw);
    }

    private static Result place(Context ctx, JsonObject args) {
        BlockPos pos = reachable(ctx, args);
        Identifier id = Identifier.tryParse(text(args, "block"));
        require(id != null && Registries.ITEM.get(id) instanceof BlockItem, "Use a known block item ID.");
        BlockItem item = (BlockItem) Registries.ITEM.get(id);
        Inventory inv = ctx.inventory();
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.getStack(slot);
            if (!stack.isOf(item)) {
                continue;
            }
            return placeCarried(ctx, pos, item, stack)
                    ? ok("Placed " + id + ".", Position.of(pos))
                    : error("The block cannot be placed there. Choose empty space next to a solid face.");
        }
        return error("The actor does not carry " + id + ".");
    }

    private static Result craft(Context ctx, JsonObject args) {
        Identifier id = Identifier.tryParse(text(args, "item"));
        require(id != null && Registries.ITEM.containsId(id), "Use a known output item ID.");
        int batches = args.has("batches") ? number(args, "batches") : 1;
        require(batches >= 1 && batches <= AgentCrafting.MAX_BATCHES, "Use 1 to 16 recipe batches.");
        try {
            var result = AgentCrafting.craft(ctx.world(), ctx.inventory(), Registries.ITEM.get(id), batches,
                    () -> hasCraftingTable(ctx));
            AgentRecipeBook.get(ctx.world()).record(ctx.actor().getUuid(), result);
            return ok("Crafted " + result.count() + " " + result.item() + ".", result);
        } catch (IllegalArgumentException invalid) {
            return error(invalid.getMessage());
        }
    }

    private static Result recipeBook(Context ctx, JsonObject args) {
        String section = args.has("section") ? text(args, "section") : "starter";
        require(section.equals("starter") || section.equals("craftable") || section.equals("crafted"),
                "Use starter, craftable, or crafted.");
        int offset = args.has("offset") ? number(args, "offset") : 0;
        require(offset >= 0, "Use an offset of zero or more.");
        var page = AgentRecipeBook.get(ctx.world()).page(ctx.world(), ctx.actor(), hasCraftingTable(ctx), section, offset);
        return ok(page.recipes().size() + " recipe(s) on the " + section + " page.", page);
    }

    private static boolean hasCraftingTable(Context ctx) {
        BlockPos center = BlockPos.ofFloored(ctx.eye());
        for (BlockPos pos : BlockPos.iterate(center.add(-5, -5, -5), center.add(5, 5, 5))) {
            if (within(ctx, pos, REACH) && loaded(ctx, pos) && ctx.world().getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)
                    && visible(ctx, Vec3d.ofCenter(pos), pos)) {
                return true;
            }
        }
        return false;
    }

    private static Result placeSign(Context ctx, JsonObject args) {
        String label = text(args, "label").strip();
        require(!label.isEmpty() && label.codePointCount(0, label.length()) <= 15
                && label.codePoints().noneMatch(Character::isISOControl), "Use a label of 1 to 15 characters on one line.");
        BlockPos pos = reachable(ctx, args);
        for (int slot = 0; slot < ctx.inventory().size(); slot++) {
            ItemStack stack = ctx.inventory().getStack(slot);
            if (!(stack.getItem() instanceof SignItem item) || stack.isEmpty()) {
                continue;
            }
            require(placeCarried(ctx, pos, item, stack), "Choose empty space with a solid floor or wall for the sign.");
            require(ctx.world().getBlockEntity(pos) instanceof SignBlockEntity, "The placed sign could not be labeled.");
            SignBlockEntity sign = (SignBlockEntity) ctx.world().getBlockEntity(pos);
            Text name = Text.literal(label);
            sign.setText(sign.getFrontText().withMessage(0, name), true);
            sign.setText(sign.getBackText().withMessage(0, name), false);
            return ok("Placed sign " + label + ".", Map.of("label", label, "target", "sign:" + label,
                    "position", Position.of(pos)));
        }
        return error("The actor does not carry a sign.");
    }

    private static boolean placeCarried(Context ctx, BlockPos pos, BlockItem item, ItemStack stack) {
        for (Direction neighborOffset : Direction.values()) {
            BlockPos neighbor = pos.offset(neighborOffset);
            Direction face = neighborOffset.getOpposite();
            if (!loaded(ctx, neighbor)
                    || !ctx.world().getBlockState(neighbor).isSideSolidFullSquare(ctx.world(), neighbor, face)) {
                continue;
            }
            var placement = new AutomaticItemPlacementContext(ctx.world(), pos,
                    ctx.actor().getHorizontalFacing(), stack, face);
            if (item.place(placement).isAccepted()) {
                ctx.inventory().markDirty();
                return true;
            }
        }
        return false;
    }

    private static Result breakBlock(Context ctx, JsonObject args) {
        BlockPos pos = reachable(ctx, args);
        return harvest(ctx, pos, toolSlot(ctx, args), false);
    }

    private static Result gather(Context ctx, JsonObject args) {
        requireIdle(ctx);
        String name = text(args, "block");
        Predicate<BlockState> matches = blockMatcher(name);
        int slot = toolSlot(ctx, args);
        var toolsByState = new HashMap<BlockState, Integer>();
        BlockPos origin = ctx.actor().getBlockPos();
        int radius = AgentNavigation.MAX_RADIUS;
        var candidates = new ArrayList<BlockPos>();
        boolean needsTool = false;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -4, -radius), origin.add(radius, 5, radius))) {
            if (!loaded(ctx, pos) || !ctx.world().getWorldBorder().contains(pos)) {
                continue;
            }
            BlockState state = ctx.world().getBlockState(pos);
            if (!state.isAir() && !(state.getBlock() instanceof FluidBlock) && matches.test(state)
                    && state.getHardness(ctx.world(), pos) >= 0 && exposed(ctx, pos)) {
                int selected = toolsByState.computeIfAbsent(state, block -> gatherTool(ctx, block, slot));
                if (selected == NO_GATHER_TOOL) {
                    needsTool = true;
                } else {
                    candidates.add(pos.toImmutable());
                }
            }
        }
        require(!candidates.isEmpty(), needsTool ? "Choose a suitable carried tool for " + name + "."
                : "No exposed " + name + " blocks were found in the loaded local area.");
        candidates.sort(Comparator.comparingDouble(pos -> Vec3d.ofCenter(pos).squaredDistanceTo(ctx.eye())));
        try {
            List<BlockPos> targets = candidates.subList(0, Math.min(GATHER_TARGET_LIMIT, candidates.size()));
            var route = AgentNavigation.startGather(ctx.world(), ctx.actor(), targets, pos -> {
                try {
                    require(canReachBlock(ctx.world(), ctx.actor(), ctx.eye(), pos), "The resource is out of reach.");
                    BlockState state = ctx.world().getBlockState(pos);
                    require(matches.test(state) && !(state.getBlock() instanceof FluidBlock), "The resource is gone or has changed.");
                    int selected = gatherTool(ctx, state, slot);
                    require(selected != NO_GATHER_TOOL, "The actor needs a suitable carried tool.");
                    Result result = harvest(ctx, pos, selected, true);
                    if (result.ok()) {
                        AgentMemory.get(ctx.world()).rememberAction(ctx.actor().getUuid(), "gather",
                                result.message(), ctx.world().getTime());
                    }
                    return result;
                } catch (ToolFailure failure) {
                    return error(failure.getMessage());
                }
            });
            return route.active() || route.completed() ? ok(route.message(), route) : error(route.message());
        } catch (IllegalArgumentException invalid) {
            return error(invalid.getMessage());
        }
    }

    private static int gatherTool(Context ctx, BlockState state, int requestedSlot) {
        boolean required = state.isToolRequired();
        if (requestedSlot >= 0) {
            return !required || toolAt(ctx, requestedSlot).isSuitableFor(state) ? requestedSlot : NO_GATHER_TOOL;
        }
        int best = required ? NO_GATHER_TOOL : -1;
        int bestPriority = required ? 0 : 1;
        float bestSpeed = 1;
        for (int slot = 0; slot < ctx.inventory().size(); slot++) {
            ItemStack stack = ctx.inventory().getStack(slot);
            boolean suitable = stack.isSuitableFor(state);
            if (stack.isEmpty() || (required && !suitable)) {
                continue;
            }
            int priority = suitable || (stack.isOf(Items.SHEARS) && needsPlantShears(state)) ? 2 : 1;
            if (stack.getMaxDamage() > 0 && EnchantmentHelper.getLevel(Enchantments.SILK_TOUCH, stack) > 0) {
                priority = 3;
            }
            float speed = stack.getMiningSpeedMultiplier(state);
            if (priority > bestPriority || (priority == bestPriority && speed > bestSpeed)) {
                bestPriority = priority;
                bestSpeed = speed;
                best = slot;
            }
        }
        return best;
    }

    private static boolean needsPlantShears(BlockState state) {
        return state.isOf(Blocks.GRASS) || state.isOf(Blocks.TALL_GRASS) || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.LARGE_FERN) || state.isOf(Blocks.DEAD_BUSH) || state.isOf(Blocks.HANGING_ROOTS);
    }

    private static Predicate<BlockState> blockMatcher(String name) {
        boolean tagged = name.startsWith("#");
        Identifier id = Identifier.tryParse(tagged ? name.substring(1) : name);
        require(id != null, "Use a block ID or a block tag beginning with #.");
        if (tagged) {
            TagKey<Block> tag = TagKey.of(RegistryKeys.BLOCK, id);
            require(Registries.BLOCK.getEntryList(tag).isPresent(), "Use a known block tag.");
            return state -> state.isIn(tag);
        }
        require(Registries.BLOCK.containsId(id), "Use a known block ID.");
        Block block = Registries.BLOCK.get(id);
        return state -> state.isOf(block);
    }

    private static boolean exposed(Context ctx, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.offset(direction);
            if (loaded(ctx, neighbor) && !ctx.world().getBlockState(neighbor).isOpaqueFullCube(ctx.world(), neighbor)) {
                return true;
            }
        }
        return false;
    }

    private static Result harvest(Context ctx, BlockPos pos, int slot, boolean collect) {
        BlockState state = ctx.world().getBlockState(pos);
        require(!state.isAir() && state.getHardness(ctx.world(), pos) >= 0, "That block cannot be broken.");
        ItemStack tool = toolAt(ctx, slot);
        require(!state.isToolRequired() || tool.isSuitableFor(state), "Choose a suitable carried tool for this block.");
        var blockEntity = ctx.world().getBlockEntity(pos);
        require(ctx.world().breakBlock(pos, false, ctx.actor()), "Minecraft could not break that block.");
        var collected = new LinkedHashMap<Identifier, Integer>();
        if (collect) {
            collectDrops(ctx, pos, state, blockEntity, tool, collected);
        } else {
            Block.dropStacks(state, ctx.world(), pos, blockEntity, ctx.actor(), tool);
        }
        if (tool.isDamageable() && state.getHardness(ctx.world(), pos) > 0) {
            if (tool.damage(1, ctx.actor().getRandom(), null)) {
                tool.decrement(1);
                tool.setDamage(0);
            }
            ctx.inventory().markDirty();
        }
        if (!collect) {
            return ok("Broke " + Registries.BLOCK.getId(state.getBlock()) + ".", Position.of(pos));
        }
        if (collected.isEmpty()) {
            return error("The block broke and no items were collected.");
        }
        var drops = collected.entrySet().stream().map(entry -> entry.getValue() + " " + entry.getKey()).toList();
        return ok("Gathered " + String.join(", ", drops) + " at " + pos.toShortString() + ".", Position.of(pos));
    }

    private static void collectDrops(Context ctx, BlockPos pos, BlockState state, BlockEntity blockEntity,
                                     ItemStack tool, Map<Identifier, Integer> collected) {
        if (ctx.world().getGameRules().getBoolean(GameRules.DO_TILE_DROPS)) {
            for (ItemStack stack : Block.getDroppedStacks(state, ctx.world(), pos, blockEntity, ctx.actor(), tool)) {
                int moved = AgentInventory.insert(ctx.inventory(), stack, stack.getCount());
                if (moved > 0) {
                    collected.merge(Registries.ITEM.getId(stack.getItem()), moved, Integer::sum);
                }
                if (moved < stack.getCount()) {
                    Block.dropStack(ctx.world(), pos, stack.copyWithCount(stack.getCount() - moved));
                }
            }
        }
        state.onStacksDropped(ctx.world(), pos, tool, true);
    }

    private static int toolSlot(Context ctx, JsonObject args) {
        if (!args.has("tool_slot")) {
            return -1;
        }
        int slot = number(args, "tool_slot");
        require(slot >= 0 && slot < ctx.inventory().size(), "The tool slot is out of range.");
        return slot;
    }

    private static ItemStack toolAt(Context ctx, int slot) {
        return slot < 0 ? ItemStack.EMPTY : ctx.inventory().getStack(slot);
    }

    private static Result pickup(Context ctx, JsonObject args) {
        Entity entity = ctx.world().getEntityById(number(args, "entity_id"));
        require(entity instanceof ItemEntity && entity.isAlive(), "The dropped item is unavailable.");
        ItemEntity item = (ItemEntity) entity;
        require(ctx.actor().squaredDistanceTo(item) <= 4 && !item.cannotPickup()
                        && visible(ctx, item.getPos().add(0, 0.1, 0), null),
                "Move within two blocks of an unobstructed, pickable item.");
        ItemStack stack = item.getStack();
        int moved = AgentInventory.insert(ctx.inventory(), stack, stack.getCount());
        require(moved > 0, "The actor's inventory is full.");
        if (moved == stack.getCount()) {
            item.discard();
        } else {
            item.setStack(stack.copyWithCount(stack.getCount() - moved));
        }
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        return ok("Picked up " + moved + " " + id + ".", Map.of("count", moved, "item", id));
    }

    private static Result openChest(Context ctx, JsonObject args) {
        BlockPos pos = reachable(ctx, args);
        Inventory chest = chest(ctx, pos);
        ctx.world().playSound(null, pos, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.5F, 1);
        ctx.world().spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5,
                5, 0.2, 0.15, 0.2, 0);
        return ok("Chest contents.", AgentInventory.slots(chest));
    }

    private static Inventory chest(Context ctx, BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        require(state.getBlock() instanceof ChestBlock, "There is no chest at that position.");
        BlockPos partner = pos.offset(ChestBlock.getFacing(state));
        require(unlocked(ctx, pos)
                        && (state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE
                        || (loaded(ctx, partner) && unlocked(ctx, partner))),
                "The chest is locked or unavailable.");
        Inventory chest = ChestBlock.getInventory((ChestBlock) state.getBlock(), state, ctx.world(), pos, false);
        require(chest != null, "The chest is blocked or unavailable.");
        return chest;
    }

    private static boolean unlocked(Context ctx, BlockPos pos) {
        var entity = ctx.world().getBlockEntity(pos);
        return entity != null && entity.createNbt().getString("Lock").isEmpty();
    }

    private static Result transfer(Context ctx, JsonObject args, boolean take) {
        Inventory chest = chest(ctx, reachable(ctx, args));
        Inventory source = take ? chest : ctx.inventory();
        Inventory destination = take ? ctx.inventory() : chest;
        int slot = number(args, "slot");
        int count = number(args, "count");
        require(slot >= 0 && slot < source.size() && count > 0, "Use a valid source slot and a positive count.");
        ItemStack stack = source.getStack(slot);
        require(!stack.isEmpty(), "The source slot is empty.");
        int moved = AgentInventory.insert(destination, stack, Math.min(count, stack.getCount()));
        require(moved > 0, "The destination inventory is full.");
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        source.removeStack(slot, moved);
        source.markDirty();
        return ok((take ? "Took " : "Stored ") + moved + " " + id + ".", Map.of("count", moved, "item", id));
    }

    private static Result talk(Context ctx, JsonObject args) {
        String target = text(args, "target");
        String message = text(args, "message").strip();
        require(!message.isEmpty() && message.length() <= 160 && message.chars().noneMatch(Character::isISOControl),
                "Use a single line of 1 to 160 characters.");
        List<VillagerEntity> recipients = ctx.world().getEntitiesByClass(VillagerEntity.class,
                ctx.actor().getBoundingBox().expand(TALK_RANGE), actor -> isActor(actor) && actor != ctx.actor()
                        && ctx.actor().squaredDistanceTo(actor) <= TALK_RANGE * TALK_RANGE
                        && (actor.getUuidAsString().equalsIgnoreCase(target)
                        || actor.getName().getString().equalsIgnoreCase(target)));
        require(recipients.size() == 1, "Choose a unique actor within eight blocks.");
        VillagerEntity recipient = recipients.get(0);
        String from = ctx.actor().getName().getString();
        String to = recipient.getName().getString();
        long tick = ctx.world().getTime();
        AgentMailbox.get(ctx.world()).deliver(recipient.getUuid(),
                new AgentMailbox.Message(ctx.actor().getUuid(), from, message, tick));
        AgentMemory.get(ctx.world()).rememberConversation(ctx.actor().getUuid(), from,
                recipient.getUuid(), to, message, tick);
        for (ServerPlayerEntity player : ctx.world().getPlayers(player -> player.squaredDistanceTo(ctx.actor()) <= 256)) {
            player.sendMessage(Text.literal("[Agent] " + from + " → " + to + ": " + message));
        }
        return ok("Message delivered to " + to + ".", Map.of("recipient", recipient.getUuid()));
    }

    private static BlockPos reachable(Context ctx, JsonObject args) {
        BlockPos pos = new BlockPos(number(args, "x"), number(args, "y"), number(args, "z"));
        require(canReachBlock(ctx.world(), ctx.actor(), ctx.eye(), pos),
                "Choose a visible block position within 4.5 blocks.");
        return pos;
    }

    static boolean canReachBlock(ServerWorld world, VillagerEntity actor, Vec3d eye, BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        return eye.squaredDistanceTo(center) <= REACH * REACH && loaded(world, pos)
                && world.getWorldBorder().contains(pos) && visible(world, actor, eye, center, pos);
    }

    private static void requireIdle(Context ctx) {
        require(!AgentCombat.isActive(ctx.world(), ctx.actor().getUuid()), "An attack is in progress.");
        require(!AgentNavigation.isActive(ctx.world(), ctx.actor().getUuid()), "A route is already in progress.");
    }

    private static boolean within(Context ctx, BlockPos pos, double range) {
        return ctx.eye().squaredDistanceTo(Vec3d.ofCenter(pos)) <= range * range;
    }

    private static boolean loaded(Context ctx, BlockPos pos) {
        return loaded(ctx.world(), pos);
    }

    private static boolean loaded(ServerWorld world, BlockPos pos) {
        return !world.isOutOfHeightLimit(pos) && world.isChunkLoaded(ChunkPos.toLong(pos));
    }

    private static boolean visible(Context ctx, Vec3d point, BlockPos target) {
        return visible(ctx.world(), ctx.actor(), ctx.eye(), point, target);
    }

    private static boolean visible(ServerWorld world, Entity actor, Vec3d eye, Vec3d point, BlockPos target) {
        var hit = world.raycast(new RaycastContext(eye, point,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, actor));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    private static Tool tool(String name, String description, BiFunction<Context, JsonObject, Result> action,
            Parameter... parameters) {
        return new Tool(new Descriptor(name, description, List.of(parameters)), action);
    }

    private static Parameter integer(String name, String description) {
        return new Parameter(name, "integer", description, true);
    }

    private static Parameter string(String name, String description) {
        return new Parameter(name, "string", description, true);
    }

    private static Parameter optional(String name, String type, String description) {
        return new Parameter(name, type, description, false);
    }

    static void validate(Descriptor tool, JsonObject args) {
        require(args != null, "Supply a JSON argument object.");
        for (String name : args.keySet()) {
            require(tool.parameters().stream().anyMatch(parameter -> parameter.name().equals(name)),
                    "Unexpected argument " + name + ".");
        }
        for (Parameter parameter : tool.parameters()) {
            String name = parameter.name();
            if (!args.has(name)) {
                require(!parameter.required(), "Missing argument " + name + ".");
                continue;
            }
            JsonElement value = args.get(name);
            boolean valid = switch (parameter.type()) {
                case "array" -> value.isJsonArray();
                case "object" -> value.isJsonObject();
                case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
                case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
                default -> throw new IllegalStateException("Unknown parameter type " + parameter.type());
            };
            require(valid, "Use a JSON " + parameter.type() + " for " + name + ".");
            if (parameter.type().equals("integer")) {
                number(args, name);
            }
        }
    }

    static int number(JsonObject args, String name) {
        try {
            return args.get(name).getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new ToolFailure("Use a whole number in range for " + name + ".");
        }
    }

    private static String text(JsonObject args, String name) {
        return args.get(name).getAsString();
    }

    static String boundedText(JsonObject args, String name, int length, boolean required) {
        String value = args.has(name) ? text(args, name).strip() : "";
        require((!required || !value.isEmpty()) && value.length() <= length
                        && value.chars().noneMatch(Character::isISOControl),
                "Use " + (required ? "1 to " : "at most ") + length + " characters for " + name + ".");
        return value;
    }

    static Result ok(String message, Object data) {
        return new Result(true, message, GSON.toJsonTree(data));
    }

    static Result error(String message) {
        return new Result(false, message, JsonNull.INSTANCE);
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new ToolFailure(message);
        }
    }

    private static final class ToolFailure extends RuntimeException {
        ToolFailure(String message) {
            super(message);
        }
    }
}
