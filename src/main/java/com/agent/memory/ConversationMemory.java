package com.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class ConversationMemory implements Memory {

    private final LinkedHashMap<String, MemoryEntry> entries = new LinkedHashMap<>();
    private int maxTokens;
    private int currentTokens;
    private final List<MemoryEntry> compressedSummaries = new ArrayList<>();

    public ConversationMemory(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    @Override
    public void store(MemoryEntry entry) {
        entries.put(entry.getId(), entry);
        currentTokens += entry.getTokenCount();
        while (currentTokens > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);
        return entries.values().stream()
                .filter(entry -> MemoryQueryTokenizer.matches(entry.getContent(), queryTokens))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            currentTokens -= removed.getTokenCount();
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        entries.clear();
        currentTokens = 0;
        compressedSummaries.clear();
    }

    @Override
    public int getTokenCount() {
        return currentTokens;
    }

    @Override
    public int size() {
        return entries.size();
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        this.maxTokens = maxTokens;
        while (currentTokens > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    private void evictOldest() {
        Iterator<Map.Entry<String, MemoryEntry>> it = entries.entrySet().iterator();
        if (it.hasNext()) {
            Map.Entry<String, MemoryEntry> oldest = it.next();
            it.remove();
            currentTokens -= oldest.getValue().getTokenCount();
            compressedSummaries.add(oldest.getValue());
        }
    }

    public String getStatusSummary() {
        return String.format("短期记忆: %d条 / %d tokens (预算: %d, 使用率: %.0f%%)",
                entries.size(), currentTokens, maxTokens, getUsageRatio() * 100);
    }

    public double getUsageRatio() {
        return maxTokens > 0 ? (double) currentTokens / maxTokens : 0;
    }
}
