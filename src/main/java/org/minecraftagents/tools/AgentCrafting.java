package org.minecraftagents.tools;

import com.google.gson.JsonElement;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeMatcher;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.DefaultedList;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

final class AgentCrafting {
    static final int MAX_BATCHES = 16;

    record Crafted(String item, int count, int batches, String recipe) {
    }

    record RecipeInfo(String recipe, String item, Integer yield, Boolean table, String ingredients) {
    }

    private AgentCrafting() {
    }

    static Crafted craft(ServerWorld world, Inventory inventory, Item item, int batches, BooleanSupplier nearTable) {
        var registries = world.getRegistryManager();
        var recipes = recipes(world).stream()
                .filter(recipe -> recipe.getOutput(registries).isOf(item))
                .sorted(Comparator.<CraftingRecipe, Boolean>comparing(recipe -> !recipe.fits(2, 2))
                        .thenComparing(CraftingRecipe::getId))
                .toList();
        String id = Registries.ITEM.getId(item).toString();
        if (recipes.isEmpty()) {
            throw new IllegalArgumentException("No shaped or shapeless crafting recipe makes " + id + ".");
        }
        boolean needsTable = false;
        for (var recipe : recipes) {
            int size = recipe.fits(2, 2) ? 2 : 3;
            if (!recipe.fits(size, size)) {
                continue;
            }
            if (size == 3 && !nearTable.getAsBoolean()) {
                needsTable = true;
                continue;
            }
            var staged = copyInventory(inventory);
            int produced = 0;
            for (int batch = 0; batch < batches; batch++) {
                int count = craftBatch(world, staged, recipe, size);
                if (count == 0) {
                    produced = 0;
                    break;
                }
                produced += count;
            }
            if (produced > 0) {
                for (int slot = 0; slot < inventory.size(); slot++) {
                    inventory.setStack(slot, staged.getStack(slot));
                }
                inventory.markDirty();
                return new Crafted(id, produced, batches, recipe.getId().toString());
            }
        }
        if (needsTable && recipes.stream().noneMatch(recipe -> recipe.fits(2, 2))) {
            throw new IllegalArgumentException("Move within reach of a visible crafting table for " + id + ".");
        }
        throw new IllegalArgumentException("Need enough ingredients and inventory space for " + id
                + ". Recipe inputs include " + ingredients(recipes.get(0)) + ".");
    }

    static List<CraftingRecipe> recipes(ServerWorld world) {
        return world.getRecipeManager().listAllOfType(RecipeType.CRAFTING).stream()
                .filter(recipe -> recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)
                .sorted(Comparator.comparing(CraftingRecipe::getId))
                .toList();
    }

    static Stream<CraftingRecipe> craftable(ServerWorld world, Inventory inventory, boolean table) {
        if (inventory.isEmpty()) {
            return Stream.empty();
        }
        var matcher = matcher(inventory);
        int availableSize = table ? 3 : 2;
        return recipes(world).stream().filter(recipe -> recipe.fits(availableSize, availableSize))
                .filter(recipe -> matcher.match(recipe, null))
                .filter(recipe -> craftBatch(world, copyInventory(inventory), recipe, recipe.fits(2, 2) ? 2 : 3) > 0);
    }

    static RecipeInfo describe(ServerWorld world, CraftingRecipe recipe) {
        ItemStack output = recipe.getOutput(world.getRegistryManager());
        return new RecipeInfo(recipe.getId().toString(), Registries.ITEM.getId(output.getItem()).toString(),
                output.getCount(), !recipe.fits(2, 2), ingredients(recipe));
    }

    private static SimpleInventory copyInventory(Inventory inventory) {
        var copy = new SimpleInventory(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            copy.setStack(slot, inventory.getStack(slot).copy());
        }
        return copy;
    }

    private static RecipeMatcher matcher(Inventory inventory) {
        var matcher = new RecipeMatcher();
        for (int slot = 0; slot < inventory.size(); slot++) {
            matcher.addUnenchantedInput(inventory.getStack(slot));
        }
        return matcher;
    }

    private static int craftBatch(ServerWorld world, SimpleInventory inventory, CraftingRecipe recipe, int size) {
        var inputs = new IntArrayList();
        if (!matcher(inventory).match(recipe, inputs)) {
            return 0;
        }
        int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : size;
        var stacks = DefaultedList.ofSize(size * size, ItemStack.EMPTY);
        for (int index = 0; index < inputs.size(); index++) {
            int item = inputs.getInt(index);
            if (item != 0) {
                stacks.set(index / width * size + index % width, takeInput(inventory, item));
            }
        }

        // Recipes only read this populated grid. The actor has no player screen handler.
        var grid = new CraftingInventory(null, size, size, stacks);
        if (!recipe.matches(grid, world)) {
            return 0;
        }
        ItemStack result = recipe.craft(grid, world.getRegistryManager());
        for (ItemStack remainder : recipe.getRemainder(grid)) {
            if (!remainder.isEmpty() && !insertAll(inventory, remainder)) {
                return 0;
            }
        }
        return result.isEmpty() || !insertAll(inventory, result) ? 0 : result.getCount();
    }

    private static ItemStack takeInput(SimpleInventory inventory, int item) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && RecipeMatcher.getItemId(stack) == item
                    && !stack.isDamaged() && !stack.hasEnchantments() && !stack.hasCustomName()) {
                return inventory.removeStack(slot, 1);
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean insertAll(Inventory inventory, ItemStack stack) {
        return AgentInventory.insert(inventory, stack, stack.getCount()) == stack.getCount();
    }

    private static String ingredients(CraftingRecipe recipe) {
        return recipe.getIngredients().stream().filter(ingredient -> !ingredient.isEmpty())
                .map(AgentCrafting::ingredientName)
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().map(entry -> entry.getValue() + "x " + entry.getKey())
                .collect(Collectors.joining(", "));
    }

    private static String ingredientName(Ingredient ingredient) {
        JsonElement json = ingredient.toJson();
        var options = json.isJsonArray() ? StreamSupport.stream(json.getAsJsonArray().spliterator(), false)
                : Stream.of(json);
        return options.map(JsonElement::getAsJsonObject)
                .map(option -> option.has("tag") ? "#" + option.get("tag").getAsString() : option.get("item").getAsString())
                .collect(Collectors.joining("/"));
    }
}
