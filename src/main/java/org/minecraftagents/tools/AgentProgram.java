package org.minecraftagents.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.minecraftagents.tools.AgentTools.require;

/** A bounded interpreter for programs that can only observe blocks and dispatch virtual input. */
final class AgentProgram {
    static final int MAX_SOURCE_LENGTH = 6000;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_DISPATCHES = 128;
    private static final int MAX_INSTRUCTIONS = 384;
    static final int MAX_TICKS = 2400;

    private AgentProgram() {
    }

    static String source(JsonElement program) {
        return program.isJsonPrimitive() && program.getAsJsonPrimitive().isString() ? program.getAsString() : program.toString();
    }

    static JsonArray parse(JsonElement program) {
        if (!program.isJsonArray()) {
            require(program.isJsonPrimitive() && program.getAsJsonPrimitive().isString(), "Supply a JSON array as the program.");
            return parse(program.getAsString());
        }
        var body = program.getAsJsonArray();
        require(body.toString().length() <= MAX_SOURCE_LENGTH, "Keep the program under 6,000 characters.");
        return validate(body.deepCopy());
    }

    static JsonArray parse(String source) {
        require(source.length() <= MAX_SOURCE_LENGTH, "Keep the program under 6,000 characters.");
        try {
            JsonElement tree = JsonParser.parseString(source);
            require(tree.isJsonArray(), "Supply a JSON array as the program.");
            return validate(tree.getAsJsonArray());
        } catch (JsonParseException invalid) {
            String detail = invalid.getCause() == null ? invalid.getMessage() : invalid.getCause().getMessage();
            throw new AgentTools.ToolFailure("Invalid program JSON. " + detail);
        }
    }

    private static JsonArray validate(JsonArray body) {
        compile(body, 0, null, null);
        return body;
    }

    private interface Instruction {
        void execute(Execution run, ServerWorld world, VillagerEntity actor);
    }

    @FunctionalInterface
    private interface Condition {
        boolean test(Execution run, ServerWorld world, VillagerEntity actor);
    }

    private record Check(JsonElement source, Condition condition) {
        boolean test(String operation, Execution run, ServerWorld world, VillagerEntity actor) {
            boolean matched = condition.test(run, world, actor);
            run.trace(world, actor, operation + " " + source + " -> " + matched);
            return matched;
        }
    }

    @FunctionalInterface
    private interface Expression {
        int evaluate(ServerWorld world, VillagerEntity actor);
    }

    private record Argument(String name, Expression expression) {
    }

    private record Location(Location parent, String block, int index) {
        JsonObject step(JsonArray program) {
            var body = parent == null ? program : parent.step(program).getAsJsonArray(block);
            return body.get(index).getAsJsonObject();
        }

        @Override
        public String toString() {
            return parent == null ? Integer.toString(index + 1) : parent + "." + block + "." + (index + 1);
        }
    }

    record CallPath(int id, AgentSkills.Ref skill, CallPath parent, Location site) {
        void replaceCall(JsonArray program, AgentSkills.Ref replacement) {
            var step = site.step(program);
            require(skill.json().equals(step.get("call")), "The recorded call has changed.");
            step.add("call", replacement.json());
        }

        boolean descendsFrom(CallPath ancestor) {
            for (var call = parent; call != null; call = call.parent()) {
                if (call == ancestor) {
                    return true;
                }
            }
            return false;
        }

        JsonArray lineage() {
            var path = new ArrayDeque<CallPath>();
            for (var call = this; call != null; call = call.parent()) {
                path.addFirst(call);
            }
            var result = new JsonArray();
            path.forEach(call -> result.add(call.json()));
            return result;
        }

        private JsonObject json() {
            var data = skill.json();
            data.addProperty("call_id", id);
            if (parent != null) {
                data.addProperty("parent_call_id", parent.id());
                data.addProperty("call_site", site.toString());
            }
            return data;
        }
    }

    private record Input(String tool, JsonObject args, List<Argument> expressions) implements Instruction {
        @Override
        public void execute(Execution run, ServerWorld world, VillagerEntity actor) {
            require(++run.calls <= MAX_DISPATCHES, "Input budget reached.");
            var bound = args.deepCopy();
            try {
                for (var argument : expressions) {
                    bound.addProperty(argument.name(), argument.expression().evaluate(world, actor));
                }
            } catch (AgentTools.ToolFailure failure) {
                run.trace(world, actor, tool + " " + args + " -> could not resolve arguments. " + failure.getMessage());
                throw failure;
            }
            var result = AgentTools.call(world, actor, tool, bound, true);
            run.trace(world, actor, tool + " " + bound + " -> " + result.message());
            require(result.ok(), result.message());
            if (tool.equals("mouse") && bound.has("button")) {
                var hit = AgentVision.target(world, actor, AgentVision.REACH);
                String target = hit.getType() == HitResult.Type.BLOCK
                        ? Registries.BLOCK.getId(world.getBlockState(hit.getBlockPos()).getBlock())
                                + " at " + hit.getBlockPos().toShortString()
                        : "no block within reach";
                run.trace(world, actor, "Crosshair after " + bound.get("button").getAsString() + " click: " + target);
            }
        }
    }

    private record Branch(Check check, List<Instruction> yes, List<Instruction> no) implements Instruction {
        @Override
        public void execute(Execution run, ServerWorld world, VillagerEntity actor) {
            run.prepend(check.test("if", run, world, actor) ? yes : no);
        }
    }

    private record Loop(Check check, int limit, List<Instruction> body) implements Instruction {
        @Override
        public void execute(Execution run, ServerWorld world, VillagerEntity actor) {
            if (limit == 0) {
                run.trace(world, actor, "while " + check.source() + " -> iteration limit reached");
            } else if (check.test("while (" + limit + " iterations left)", run, world, actor)) {
                run.prepend(List.of(new Loop(check, limit - 1, body)));
                run.prepend(body);
            }
        }
    }

    private record Call(AgentSkills.Ref skill, Location site) implements Instruction {
        @Override
        public void execute(Execution run, ServerWorld world, VillagerEntity actor) {
            run.enter(world, actor, skill, site);
        }
    }

    static final class Execution {
        private static final int RECENT_CALL_LIMIT = 14;

        private record Frame(CallPath path, Frame caller, Map<String, Integer> startingInventory,
                             ArrayDeque<Instruction> pending) {
            Frame(CallPath path, Frame caller, VillagerEntity actor) {
                this(path, caller, AgentInventory.totals(actor.getInventory()), new ArrayDeque<>());
            }
        }

        private record CompletedCall(CallPath path, JsonObject evidence) {
        }

        private final ArrayDeque<CompletedCall> recentCalls = new ArrayDeque<>();
        private final CallPath root;
        private final AgentDebug.State debug;
        private Frame frame;
        private CallPath failedAt;
        private int nextCallId;
        private int queued;
        private int calls;
        private int instructions;

        Execution(AgentSkills.Ref skill, JsonArray program, VillagerEntity actor, AgentDebug.State debug) {
            this.debug = debug;
            root = new CallPath(nextCallId++, skill, null, null);
            frame = new Frame(root, null, actor);
            prepend(compile(program, 0, null, null));
        }

        private void trace(ServerWorld world, VillagerEntity actor, String detail) {
            var path = frame.path();
            debug.event(world, actor, "#" + instructions + " [" + path.skill() + " #" + path.id() + "] " + detail);
        }

        void finished(ServerWorld world, VillagerEntity actor, boolean success, String detail) {
            debug.finished(world, actor, success, detail);
        }

        JsonObject evidence() {
            var evidence = debug.evidence();
            var completed = new JsonArray();
            recentCalls.forEach(call -> completed.add(call.evidence()));
            evidence.add("completed_calls", completed);
            return evidence;
        }

        CallPath repairTarget(Integer callId) {
            var failed = failedAt == null ? root : failedAt;
            if (callId == null || callId == failed.id()) {
                return failed;
            }
            for (var call : recentCalls) {
                if (call.path().id() == callId && call.path().descendsFrom(failed)) {
                    return call.path();
                }
            }
            throw new AgentTools.ToolFailure("Choose the failed call or an observed completed call beneath it.");
        }

        private void prepend(List<Instruction> body) {
            require(queued + body.size() <= MAX_INSTRUCTIONS, "Program stack budget reached.");
            queued += body.size();
            for (int index = body.size() - 1; index >= 0; index--) {
                frame.pending().addFirst(body.get(index));
            }
        }

        private void enter(ServerWorld world, VillagerEntity actor, AgentSkills.Ref skill, Location site) {
            trace(world, actor, "call " + skill + " at " + site);
            var program = AgentSkills.inputProgram(world, actor, skill);
            frame = new Frame(new CallPath(nextCallId++, skill, frame.path(), site), frame, actor);
            prepend(compile(program, 0, null, null));
        }

        private boolean completeFrames(ServerWorld world, VillagerEntity actor) {
            while (frame != null && frame.pending().isEmpty()) {
                var path = frame.path();
                if (frame.caller() != null) {
                    var changes = new JsonObject();
                    var current = AgentInventory.totals(actor.getInventory());
                    frame.startingInventory().forEach((item, before) -> {
                        int gain = current.getOrDefault(item, 0) - before;
                        if (gain != 0) {
                            changes.addProperty(item, gain);
                        }
                    });
                    current.forEach((item, count) -> {
                        if (!frame.startingInventory().containsKey(item)) {
                            changes.addProperty(item, count);
                        }
                    });
                    var evidence = path.json();
                    evidence.add("inventory_change", changes);
                    if (recentCalls.size() == RECENT_CALL_LIMIT) {
                        recentCalls.removeFirst();
                    }
                    recentCalls.addLast(new CompletedCall(path, evidence));
                    trace(world, actor, "returned from call, inventory change " + changes);
                }
                frame = frame.caller();
            }
            return frame == null;
        }

        boolean tick(ServerWorld world, VillagerEntity actor) {
            if (AgentInput.isActive(world, actor.getUuid())) {
                return false;
            }
            if (completeFrames(world, actor)) {
                return true;
            }
            require(++instructions <= MAX_INSTRUCTIONS, "Instruction budget reached.");
            queued--;
            try {
                frame.pending().removeFirst().execute(this, world, actor);
            } catch (AgentTools.ToolFailure failure) {
                failedAt = frame.path();
                throw failure;
            }
            return !AgentInput.isActive(world, actor.getUuid()) && completeFrames(world, actor);
        }

        int calls() {
            return calls;
        }
    }

    private static List<Instruction> compile(JsonArray body, int depth, Location parent, String block) {
        require(depth <= MAX_DEPTH, "Program nesting is limited to six levels.");
        require(!body.isEmpty() && body.size() <= 64, "Supply 1 to 64 instructions in a program block.");
        List<Instruction> result = new ArrayList<>();
        for (int index = 0; index < body.size(); index++) {
            var value = body.get(index);
            require(value.isJsonObject(), "Each instruction must be an object.");
            var step = value.getAsJsonObject();
            var site = new Location(parent, block, index);
            if (step.has("if")) {
                require(Set.of("if", "then", "else").containsAll(step.keySet()), "Use if, then, and optional else.");
                result.add(new Branch(check(step.get("if")), compile(body(step, "then"), depth + 1, site, "then"),
                        step.has("else") ? compile(body(step, "else"), depth + 1, site, "else") : List.of()));
            } else if (step.has("while")) {
                require(step.keySet().equals(Set.of("while", "limit", "do")),
                        "Use {\"while\": condition, \"limit\": 8, \"do\": [...]}. Put limit and do beside while.");
                int limit = integer(step.get("limit"), "limit");
                require(limit >= 1 && limit <= 64, "A loop limit must be between 1 and 64.");
                result.add(new Loop(check(step.get("while")), limit, compile(body(step, "do"), depth + 1, site, "do")));
            } else if (step.has("assert")) {
                require(step.size() == 1, "An assert instruction takes one condition.");
                var test = check(step.get("assert"));
                result.add((run, world, actor) -> require(test.test("assert", run, world, actor),
                        "A program's observation check failed."));
            } else if (step.has("call")) {
                require(step.size() == 1 && step.get("call").isJsonObject(), "Use call with a skill name and version.");
                var call = step.getAsJsonObject("call");
                require(call.keySet().equals(Set.of("name", "version")), "Pin calls to a name and version.");
                int version = integer(call.get("version"), "version");
                require(version > 0, "Use a positive skill version.");
                result.add(new Call(new AgentSkills.Ref(string(call.get("name"), "name"), version), site));
            } else {
                require(step.size() == 1, "Use one input per instruction.");
                var input = step.entrySet().iterator().next();
                require(input.getValue().isJsonObject(), "Input arguments must be an object.");
                result.add(input(input.getKey(), input.getValue().getAsJsonObject()));
            }
        }
        return result;
    }

    private static Input input(String tool, JsonObject args) {
        var schema = AgentTools.inputPrimitive(tool);
        var sample = args.deepCopy();
        List<Argument> expressions = new ArrayList<>();
        for (var entry : args.entrySet()) {
            var expression = entry.getValue();
            if (expression.isJsonObject() && schema.parameters().stream().anyMatch(parameter ->
                    parameter.name().equals(entry.getKey()) && parameter.type().equals("integer"))) {
                expressions.add(new Argument(entry.getKey(), expression(expression.getAsJsonObject(), entry.getKey())));
                sample.addProperty(entry.getKey(), 1);
            }
        }
        AgentTools.validate(schema, sample);
        return new Input(tool, args, expressions);
    }

    private static Expression expression(JsonObject value, String name) {
        require(value.size() == 1, "Use one numeric expression per argument.");
        if (value.has("random")) {
            var range = body(value, "random");
            require(range.size() == 2, "Use random with two integer bounds.");
            int min = integer(range.get(0), "minimum");
            int max = integer(range.get(1), "maximum");
            require(min >= -360 && max <= 360 && min <= max, "Use ordered random bounds from -360 to 360.");
            return (world, actor) -> min + actor.getRandom().nextInt(max - min + 1);
        }
        if (name.equals("yaw") && value.has("bearing")) {
            var matches = AgentTools.blockMatcher(string(value.get("bearing"), "bearing"));
            return (world, actor) -> yaw(actor, AgentVision.directionToVisible(world, actor, matches));
        }
        if (name.equals("pitch") && value.has("elevation")) {
            var matches = AgentTools.blockMatcher(string(value.get("elevation"), "elevation"));
            return (world, actor) -> pitch(actor, AgentVision.directionToVisible(world, actor, matches));
        }
        if (name.equals("yaw") && value.has("item_bearing")) {
            var item = item(value.get("item_bearing"), "item_bearing");
            return (world, actor) -> yaw(actor, itemDirection(world, actor, item));
        }
        if (name.equals("pitch") && value.has("item_elevation")) {
            var item = item(value.get("item_elevation"), "item_elevation");
            return (world, actor) -> pitch(actor, itemDirection(world, actor, item));
        }
        throw new AgentTools.ToolFailure("Use random, bearing or item_bearing for yaw, or elevation or item_elevation for pitch.");
    }

    private static int yaw(VillagerEntity actor, Vec3d delta) {
        return (int) Math.round(MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(-delta.x, delta.z)) - actor.getYaw()));
    }

    private static int pitch(VillagerEntity actor, Vec3d delta) {
        return (int) Math.round(-Math.toDegrees(Math.atan2(delta.y, delta.horizontalLength())) - actor.getPitch());
    }

    private static Vec3d itemDirection(ServerWorld world, VillagerEntity actor, Item item) {
        var target = AgentVision.visibleItem(world, actor, item);
        require(target != null, "No matching dropped item is currently visible.");
        return target.getBoundingBox().getCenter().subtract(actor.getEyePos());
    }

    private static Check check(JsonElement source) {
        return new Check(source, condition(source, 0));
    }

    private static Condition condition(JsonElement value, int depth) {
        require(depth <= MAX_DEPTH && value.isJsonObject(), "Supply a structured observation condition.");
        var check = value.getAsJsonObject();
        if (check.has("not") && check.size() == 1) {
            var child = condition(check.get("not"), depth + 1);
            return (run, world, actor) -> !child.test(run, world, actor);
        }
        if (check.has("visible") && check.size() == 1) {
            var matches = AgentTools.blockMatcher(string(check.get("visible"), "visible"));
            return (run, world, actor) -> AgentVision.sees(world, actor, matches);
        }
        if (check.has("target") && Set.of("target", "within").containsAll(check.keySet())) {
            var matches = AgentTools.blockMatcher(string(check.get("target"), "target"));
            int reach = check.has("within") ? integer(check.get("within"), "within") : 12;
            require(reach >= 1 && reach <= 12, "Target range must be from 1 to 12 blocks.");
            return (run, world, actor) -> {
                var target = AgentVision.target(world, actor, reach);
                return target.getType() == HitResult.Type.BLOCK && matches.test(world.getBlockState(target.getBlockPos()));
            };
        }
        if (check.has("visible_item") && check.size() == 1) {
            var item = item(check.get("visible_item"), "visible_item");
            return (run, world, actor) -> AgentVision.visibleItem(world, actor, item) != null;
        }
        if (check.has("inventory") && check.size() == 1 && check.get("inventory").isJsonObject()) {
            return inventory(check.getAsJsonObject("inventory"));
        }
        throw new AgentTools.ToolFailure("Use visible, target, visible_item, inventory, or not.");
    }

    private static Condition inventory(JsonObject check) {
        boolean increase = check.keySet().equals(Set.of("item", "increase"));
        require(increase || check.keySet().equals(Set.of("item", "at_least")),
                "Use inventory with item and either increase or at_least.");
        var item = item(check.get("item"), "item");
        String id = Registries.ITEM.getId(item).toString();
        String field = increase ? "increase" : "at_least";
        int amount = integer(check.get(field), field);
        require(amount > 0, "Use a positive item count.");
        return (run, world, actor) -> {
            int before = run.frame.startingInventory().getOrDefault(id, 0);
            int current = AgentInventory.count(actor.getInventory(), item);
            run.trace(world, actor, "inventory " + id + " started with " + before + ", now " + current
                    + ", requires " + (increase ? "a gain of " : "at least ") + amount);
            return current - (increase ? before : 0) >= amount;
        };
    }

    private static Item item(JsonElement value, String name) {
        var id = Identifier.tryParse(string(value, name));
        require(id != null && Registries.ITEM.containsId(id), "Use a known Minecraft item ID.");
        return Registries.ITEM.get(id);
    }

    private static JsonArray body(JsonObject object, String name) {
        require(object.has(name) && object.get(name).isJsonArray(), name + " must be a JSON array.");
        return object.getAsJsonArray(name);
    }

    private static String string(JsonElement value, String name) {
        require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), name + " must be a string.");
        return value.getAsString();
    }

    private static int integer(JsonElement value, String name) {
        require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), name + " must be an integer.");
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new AgentTools.ToolFailure(name + " must be an integer.");
        }
    }
}
