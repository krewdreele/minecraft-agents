package org.minecraftagents.tools;

import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.List;

final class AgentInventory {
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
