package org.minecraftagents.tools;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The starter and available pages use the current world recipes. Personal history is saved. */
final class AgentRecipeBook extends PersistentState {
    private static final int PAGE_SIZE = 6;
    private static final List<String> STARTER = List.of("minecraft:oak_planks", "minecraft:stick",
            "minecraft:crafting_table", "minecraft:wooden_pickaxe", "minecraft:wooden_sword", "minecraft:torch");
    private final Map<UUID, LinkedHashMap<String, String>> history = new HashMap<>();

    record Page(String section, boolean tableInReach, List<AgentCrafting.RecipeInfo> recipes, Integer nextOffset) {
    }

    record Preview(boolean tableInReach, List<String> craftable, List<String> recentlyCrafted) {
    }

    static AgentRecipeBook get(ServerWorld world) {
        return world.getServer().getOverworld().getPersistentStateManager().getOrCreate(
                AgentRecipeBook::readNbt, AgentRecipeBook::new, "minecraft_agents_recipes");
    }

    void record(UUID actor, AgentCrafting.Crafted crafted) {
        remember(actor, crafted.recipe(), crafted.item());
        markDirty();
    }

    Page page(ServerWorld world, VillagerEntity actor, boolean table, String section, int offset) {
        var entries = switch (section) {
            case "starter" -> STARTER.stream()
                    .map(recipe -> describe(world, recipe, null)).filter(info -> info != null);
            case "craftable" -> AgentCrafting.craftable(world, actor.getInventory(), table)
                    .map(recipe -> AgentCrafting.describe(world, recipe));
            case "crafted" -> recent(actor.getUuid()).stream()
                    .map(entry -> describe(world, entry.getKey(), entry.getValue()));
            default -> throw new IllegalArgumentException("Use starter, craftable, or crafted.");
        };
        var window = entries.skip(offset).limit(PAGE_SIZE + 1L).toList();
        int size = Math.min(window.size(), PAGE_SIZE);
        return new Page(section, table, window.subList(0, size), window.size() > size ? offset + size : null);
    }

    Preview preview(ServerWorld world, VillagerEntity actor, boolean table) {
        var craftable = AgentCrafting.craftable(world, actor.getInventory(), table)
                .map(recipe -> Registries.ITEM.getId(recipe.getOutput(world.getRegistryManager()).getItem()).toString())
                .distinct().limit(5).toList();
        var crafted = recent(actor.getUuid()).stream().map(Map.Entry::getValue).distinct().limit(4).toList();
        return new Preview(table, craftable, crafted);
    }

    private static AgentCrafting.RecipeInfo describe(ServerWorld world, String id, String pastItem) {
        var recipe = world.getRecipeManager().get(new Identifier(id)).orElse(null);
        if (recipe instanceof CraftingRecipe crafting) {
            return AgentCrafting.describe(world, crafting);
        }
        return pastItem == null ? null : new AgentCrafting.RecipeInfo(id, pastItem, null, null, null);
    }

    private List<Map.Entry<String, String>> recent(UUID actor) {
        var known = history.get(actor);
        if (known == null) {
            return List.of();
        }
        var entries = new ArrayList<>(known.entrySet());
        java.util.Collections.reverse(entries);
        return entries;
    }

    private void remember(UUID actor, String recipe, String item) {
        var known = history.computeIfAbsent(actor, id -> new LinkedHashMap<>());
        known.remove(recipe);
        known.put(recipe, item);
    }

    @Override
    public NbtCompound writeNbt(NbtCompound root) {
        var entries = new NbtList();
        history.forEach((actor, known) -> known.forEach((recipe, item) -> {
            var entry = new NbtCompound();
            entry.putUuid("Actor", actor);
            entry.putString("Recipe", recipe);
            entry.putString("Item", item);
            entries.add(entry);
        }));
        root.put("Recipes", entries);
        return root;
    }

    private static AgentRecipeBook readNbt(NbtCompound root) {
        var book = new AgentRecipeBook();
        for (NbtElement raw : root.getList("Recipes", NbtElement.COMPOUND_TYPE)) {
            var entry = (NbtCompound) raw;
            String recipe = entry.getString("Recipe");
            if (entry.containsUuid("Actor") && !recipe.isEmpty() && Identifier.tryParse(recipe) != null) {
                book.remember(entry.getUuid("Actor"), recipe, entry.getString("Item"));
            }
        }
        return book;
    }
}
