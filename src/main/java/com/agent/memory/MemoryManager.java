package com.agent.memory;

import com.agent.llm.LlmClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private static final int DEFAULT_SHORT_TERM_BUDGET = 32_768;
    private static final double COMPRESSION_TRIGGER_RATIO = 0.9;

    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final ContextCompressor compressor;
    private final MemoryRetriever retriever;
    private final TokenBudget tokenBudget;
    private String currentProject = defaultProjectKey();

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, DEFAULT_SHORT_TERM_BUDGET, TokenBudget.defaults(), null);
    }

    public MemoryManager(LlmClient llmClient, int shortTermBudget, TokenBudget tokenBudget, LongTermMemory longTermMemory) {
        this.shortTermMemory = new ConversationMemory(shortTermBudget);
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.compressor = new ContextCompressor(llmClient);
        this.retriever = new MemoryRetriever(this.longTermMemory);
        this.tokenBudget = tokenBudget != null ? tokenBudget : TokenBudget.defaults();
    }

    public void setLlmClient(LlmClient llmClient) {
        this.compressor.setLlmClient(llmClient);
    }

    public void setProjectPath(String projectPath) {
        if (projectPath != null && !projectPath.isBlank()) {
            this.currentProject = normalizeProjectKey(projectPath);
        }
    }

    public void addUserMessage(String content) {
        storeShortTerm("user", content, MemoryEntry.MemoryType.CONVERSATION, Map.of("source", "user"));
        compressIfNeeded();
    }

    public void addAssistantMessage(String content) {
        storeShortTerm("assistant", content, MemoryEntry.MemoryType.CONVERSATION, Map.of("source", "assistant"));
        compressIfNeeded();
    }

    public void addToolResult(String toolName, String result) {
        String truncated = result != null && result.length() > 500
                ? result.substring(0, 500) + "...(已截断)"
                : result;
        storeShortTerm("tool", "[" + toolName + "] " + truncated, MemoryEntry.MemoryType.TOOL_RESULT,
                Map.of("source", "tool", "toolName", toolName));
        compressIfNeeded();
    }

    public void storeFact(String fact) {
        storeFact(fact, "project");
    }

    public void storeFact(String fact, String scope) {
        String normalizedScope = normalizeScope(scope);
        Map<String, String> metadata = "global".equals(normalizedScope)
                ? Map.of("source", "fact", "scope", "global")
                : Map.of("source", "fact", "scope", "project", "project", currentProject);
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact.trim(),
                MemoryEntry.MemoryType.FACT,
                metadata,
                MemoryEntry.estimateTokens(fact)
        );
        longTermMemory.store(entry);
    }

    public List<MemoryEntry> listLongTerm() {
        return longTermMemory.getAll(currentProject);
    }

    public List<MemoryEntry> searchLongTerm(String query, int limit) {
        return longTermMemory.search(query, limit, currentProject);
    }

    public boolean deleteLongTerm(String id) {
        return longTermMemory.delete(id);
    }

    public void clearLongTerm() {
        longTermMemory.clear();
    }

    public String buildContextForQuery(String query, int maxTokens) {
        return retriever.buildContextForQuery(query, maxTokens, currentProject);
    }

    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    public boolean compressIfNeeded() {
        if (!tokenBudget.needsCompression(shortTermMemory, COMPRESSION_TRIGGER_RATIO)) {
            return false;
        }
        log.info("短期记忆达到压缩阈值，开始压缩");
        String summary = compressor.compress(shortTermMemory);
        return summary != null;
    }

    public void clearShortTerm() {
        shortTermMemory.clear();
    }

    public String getSystemStatus() {
        return shortTermMemory.getStatusSummary() + "\n"
                + longTermMemory.getStatusSummary() + "\n"
                + tokenBudget.getUsageReport();
    }

    public ConversationMemory getShortTermMemory() {
        return shortTermMemory;
    }

    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    public TokenBudget getTokenBudget() {
        return tokenBudget;
    }

    public String getCurrentProject() {
        return currentProject;
    }

    private void storeShortTerm(String prefix, String content, MemoryEntry.MemoryType type, Map<String, String> metadata) {
        if (content == null || content.isBlank()) {
            return;
        }
        MemoryEntry entry = new MemoryEntry(
                prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                type,
                metadata,
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "project";
        }
        return "global".equalsIgnoreCase(scope.trim()) ? "global" : "project";
    }

    private static String defaultProjectKey() {
        return normalizeProjectKey(System.getProperty("user.dir"));
    }

    private static String normalizeProjectKey(String path) {
        try {
            Path candidate = Path.of(path).toAbsolutePath().normalize();
            if (java.nio.file.Files.exists(candidate)) {
                return candidate.toRealPath().toString();
            }
            return candidate.toString();
        } catch (Exception e) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }
}
