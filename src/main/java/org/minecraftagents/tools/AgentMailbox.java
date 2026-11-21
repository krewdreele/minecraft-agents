package org.minecraftagents.tools;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class AgentMailbox extends PersistentState {
    private static final int MAX_INBOX = 32;
    private final Map<UUID, ArrayDeque<Message>> messages = new HashMap<>();

    record Message(UUID sender, String name, String message, long tick) {
    }

    static AgentMailbox get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(
                AgentMailbox::readNbt, AgentMailbox::new, "minecraft_agents_mail");
    }

    List<Message> inbox(UUID recipient) {
        var inbox = messages.get(recipient);
        return inbox == null ? List.of() : List.copyOf(inbox);
    }

    void deliver(UUID recipient, Message message) {
        var inbox = messages.computeIfAbsent(recipient, id -> new ArrayDeque<>());
        if (inbox.size() == MAX_INBOX) {
            inbox.removeFirst();
        }
        inbox.addLast(message);
        markDirty();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound root) {
        var allMessages = new NbtList();
        messages.forEach((recipient, inbox) -> {
            for (Message message : inbox) {
                var entry = new NbtCompound();
                entry.putUuid("Recipient", recipient);
                entry.putUuid("Sender", message.sender());
                entry.putString("Name", message.name());
                entry.putString("Message", message.message());
                entry.putLong("Tick", message.tick());
                allMessages.add(entry);
            }
        });
        root.put("Messages", allMessages);
        return root;
    }

    private static AgentMailbox readNbt(NbtCompound root) {
        var mailbox = new AgentMailbox();
        for (NbtElement raw : root.getList("Messages", NbtElement.COMPOUND_TYPE)) {
            var entry = (NbtCompound) raw;
            if (entry.containsUuid("Recipient") && entry.containsUuid("Sender")) {
                mailbox.deliver(entry.getUuid("Recipient"), new Message(entry.getUuid("Sender"),
                        entry.getString("Name"), entry.getString("Message"), entry.getLong("Tick")));
            }
        }
        mailbox.setDirty(false);
        return mailbox;
    }
}
