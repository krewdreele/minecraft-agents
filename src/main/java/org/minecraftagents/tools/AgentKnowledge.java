package org.minecraftagents.tools;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class AgentKnowledge extends PersistentState {
    static final int MAX_TOPIC_LENGTH = 40;
    static final int MAX_NOTE_LENGTH = 180;
    static final int READ_LIMIT = 4;
    private static final int MAX_NOTES = 128;
    private final Map<Key, Note> notes = new LinkedHashMap<>();

    record Note(String author, String topic, String note, String dimension, AgentTools.Position position, long tick) {
    }

    private record Key(UUID authorId, String topic) {
    }

    static AgentKnowledge get(ServerWorld world) {
        return world.getServer().getOverworld().getPersistentStateManager().getOrCreate(
                AgentKnowledge::readNbt, AgentKnowledge::new, "minecraft_agents_knowledge");
    }

    Note write(ServerWorld world, VillagerEntity actor, String topic, String text) {
        var note = new Note(actor.getName().getString(), topic, text, world.getRegistryKey().getValue().toString(),
                AgentTools.Position.of(actor.getBlockPos()), world.getTime());
        store(actor.getUuid(), note);
        markDirty();
        return note;
    }

    List<Note> read(String query, int limit) {
        String search = normalize(query);
        var newest = new ArrayList<>(notes.values());
        var matches = new ArrayList<Note>(limit);
        for (int index = newest.size() - 1; index >= 0 && matches.size() < limit; index--) {
            Note note = newest.get(index);
            if (search.isEmpty() || normalize(note.topic()).contains(search) || normalize(note.note()).contains(search)) {
                matches.add(note);
            }
        }
        return matches;
    }

    private void store(UUID authorId, Note note) {
        var key = new Key(authorId, normalize(note.topic()));
        notes.remove(key);
        notes.put(key, note);
        if (notes.size() > MAX_NOTES) {
            notes.remove(notes.keySet().iterator().next());
        }
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    @Override
    public NbtCompound writeNbt(NbtCompound root) {
        var entries = new NbtList();
        notes.forEach((key, note) -> {
            var entry = new NbtCompound();
            entry.putUuid("AuthorId", key.authorId());
            entry.putString("Author", note.author());
            entry.putString("Topic", note.topic());
            entry.putString("Note", note.note());
            entry.putString("Dimension", note.dimension());
            entry.putInt("X", note.position().x());
            entry.putInt("Y", note.position().y());
            entry.putInt("Z", note.position().z());
            entry.putLong("Tick", note.tick());
            entries.add(entry);
        });
        root.put("Notes", entries);
        return root;
    }

    private static AgentKnowledge readNbt(NbtCompound root) {
        var knowledge = new AgentKnowledge();
        for (NbtElement raw : root.getList("Notes", NbtElement.COMPOUND_TYPE)) {
            var entry = (NbtCompound) raw;
            if (entry.containsUuid("AuthorId")) {
                var position = new AgentTools.Position(entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"));
                knowledge.store(entry.getUuid("AuthorId"), new Note(entry.getString("Author"), entry.getString("Topic"),
                        entry.getString("Note"), entry.getString("Dimension"), position, entry.getLong("Tick")));
            }
        }
        return knowledge;
    }
}
