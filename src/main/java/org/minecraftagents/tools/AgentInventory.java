package org.minecraftagents.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AgentInventory {
    private static final Gson GSON = new Gson();

    record Slot(int slot, String item, int count, Integer healing) {
    }

    private AgentInventory() {
    }

    static List<Slot> slots(Inventory inventory) {
        var slots = new ArrayList<Slot>();
        for (int index = 0; index < inventory.size(); index++) {
            ItemStack stack = inventory.getStack(index);
            if (!stack.isEmpty()) {
                var food = stack.getItem().getFoodComponent();
                slots.add(new Slot(index, Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(),
                        food == null ? null : food.getHunger()));
            }
        }
        return slots;
    }

    static JsonArray observedSlots(Inventory inventory) {
        return GSON.toJsonTree(slots(inventory)).getAsJsonArray();
    }

    static Map<String, Integer> totals(Inventory inventory) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var slot : slots(inventory)) {
            counts.merge(slot.item(), slot.count(), Integer::sum);
        }
        return counts;
    }

    static int count(Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // Insert first into matching stacks, then into empty slots. The caller removes only what fit.
    static int insert(Inventory inventory, ItemStack template, int count) {
        int remaining = count;
        for (boolean emptySlots : new boolean[]{false, true}) {
            for (int index = 0; index < inventory.size() && remaining > 0; index++) {
                ItemStack existing = inventory.getStack(index);
                if (existing.isEmpty() != emptySlots || !inventory.isValid(index, template)
                        || (!emptySlots && !ItemStack.canCombine(existing, template))) {
                    continue;
                }
                int capacity = Math.min(template.getMaxCount(), inventory.getMaxCountPerStack()) - existing.getCount();
                int added = Math.min(remaining, capacity);
                if (added > 0) {
                    inventory.setStack(index, template.copyWithCount(existing.getCount() + added));
                    remaining -= added;
                }
            }
        }
        if (remaining < count) {
            inventory.markDirty();
        }
        return count - remaining;
    }
}
