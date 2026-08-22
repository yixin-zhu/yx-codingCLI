package com.agent.memory;

import java.util.List;
import java.util.Optional;

public interface Memory {
    void store(MemoryEntry entry);

    Optional<MemoryEntry> retrieve(String id);

    List<MemoryEntry> search(String query, int limit);

    List<MemoryEntry> getAll();

    boolean delete(String id);

    void clear();

    int getTokenCount();

    int size();
}
