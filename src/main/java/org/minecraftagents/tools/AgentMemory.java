package org.minecraftagents.tools;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Per-actor milestones and conversations stored in the world save. */
final class AgentMemory extends PersistentState {
    private static final int CAPACITY = 96;
    private static final int PAGE_SIZE = 8;

    private final Map<UUID, ArrayDeque<Entry>> milestones = new HashMap<>();
    private final Map<UUID, ArrayDeque<Entry>> conversations = new HashMap<>();

    record Entry(String action, String detail, String partner, String direction, long tick) {
        boolean sameEvent(Entry other) {
            return action.equals(other.action) && detail.equals(other.detail)
                    && Objects.equals(partner, other.partner) && Objects.equals(direction, other.direction);
        }

        String searchable() {
            return normalize(action + " " + detail + (partner == null ? "" : " " + partner));
        }
    }

    record View(List<Entry> milestones, List<Entry> conversations) {
    }

    record Page(List<Entry> entries, Integer nextOffset) {
    }

    static AgentMemory get(ServerWorld world) {
        return world.getServer().getOverworld().getPersistentStateManager().getOrCreate(
                AgentMemory::readNbt, AgentMemory::new, "minecraft_agents_memory");
    }

    void rememberAction(UUID actor, String action, String detail, long tick) {
        store(milestones, actor, new Entry(action, detail, null, null, tick));
        markDirty();
    }

    void rememberConversation(UUID sender, String senderName, UUID recipient, String recipientName,
                              String message, long tick) {
        store(conversations, sender, new Entry("talk", message, recipientName, "sent", tick));
        store(conversations, recipient, new Entry("talk", message, senderName, "received", tick));
        markDirty();
    }

    View view(UUID actor, String goal) {
        var terms = List.of(normalize(goal).split("[^\\p{L}\\p{N}_]+")).stream()
                .filter(term -> term.length() >= 4).distinct().toList();
        return new View(highlights(milestones.get(actor), terms, 3),
                highlights(conversations.get(actor), terms, 4));
    }

    Page recall(UUID actor, String query, String partner, int offset) {
        var entries = new ArrayList<Entry>();
        if (partner.isEmpty()) {
            entries.addAll(milestones.getOrDefault(actor, new ArrayDeque<>()));
        }
        entries.addAll(conversations.getOrDefault(actor, new ArrayDeque<>()));
        String search = normalize(query);
        var window = entries.stream()
                .filter(entry -> partner.isEmpty() || partner.equalsIgnoreCase(entry.partner()))
                .filter(entry -> search.isEmpty() || entry.searchable().contains(search))
                .sorted(Comparator.comparingLong(Entry::tick).reversed())
                .skip(offset).limit(PAGE_SIZE + 1L).toList();
        int size = Math.min(window.size(), PAGE_SIZE);
        return new Page(window.subList(0, size), window.size() > size ? offset + size : null);
    }

    private static List<Entry> highlights(ArrayDeque<Entry> entries, List<String> terms, int recentLimit) {
        if (entries == null) {
            return List.of();
        }
        var ordered = new ArrayList<>(entries);
        var recent = new ArrayList<>(ordered.subList(Math.max(0, ordered.size() - recentLimit), ordered.size()));
        Entry relevant = null;
        long best = 0;
        for (int i = ordered.size() - recent.size() - 1; i >= 0; i--) {
            Entry entry = ordered.get(i);
            String text = entry.searchable();
            long score = terms.stream().filter(text::contains).count();
            if (score > best) {
                relevant = entry;
                best = score;
            }
        }
        if (relevant != null) {
            recent.add(0, relevant);
        }
        return recent;
    }

    private static void store(Map<UUID, ArrayDeque<Entry>> entries, UUID actor, Entry fresh) {
        var history = entries.computeIfAbsent(actor, id -> new ArrayDeque<>());
        history.removeIf(fresh::sameEvent);
        if (history.size() == CAPACITY) {
            history.removeFirst();
        }
        history.addLast(fresh);
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    @Override
    public NbtCompound writeNbt(NbtCompound root) {
        var entries = new NbtList();
        writeEntries(entries, milestones);
        writeEntries(entries, conversations);
        root.put("Memory", entries);
        return root;
    }

    private static void writeEntries(NbtList entries, Map<UUID, ArrayDeque<Entry>> history) {
        history.forEach((actor, memories) -> {
            for (Entry memory : memories) {
                var entry = new NbtCompound();
                entry.putUuid("Actor", actor);
                entry.putString("Action", memory.action());
                entry.putString("Detail", memory.detail());
                entry.putLong("Tick", memory.tick());
                if (memory.partner() != null) {
                    entry.putString("Partner", memory.partner());
                    entry.putString("Direction", memory.direction());
                }
                entries.add(entry);
            }
        });
    }

    private static AgentMemory readNbt(NbtCompound root) {
        var memory = new AgentMemory();
        for (NbtElement raw : root.getList("Memory", NbtElement.COMPOUND_TYPE)) {
            var entry = (NbtCompound) raw;
            if (entry.containsUuid("Actor")) {
                boolean conversation = entry.contains("Partner", NbtElement.STRING_TYPE);
                store(conversation ? memory.conversations : memory.milestones, entry.getUuid("Actor"),
                        new Entry(entry.getString("Action"), entry.getString("Detail"),
                                conversation ? entry.getString("Partner") : null,
                                conversation ? entry.getString("Direction") : null, entry.getLong("Tick")));
            }
        }
        return memory;
    }
}
