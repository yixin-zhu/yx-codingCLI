package com.agent.memory;

import com.agent.context.ContextProfile;
import com.agent.llm.LlmClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final ContextCompressor compressor;
    private final MemoryRetriever retriever;
    private TokenBudget tokenBudget;
    private ContextProfile contextProfile;
    private String currentProject = defaultProjectKey();

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, ContextProfile.from(llmClient), null);
    }

    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow, LongTermMemory longTermMemory) {
        this(llmClient, ContextProfile.custom(contextWindow, shortTermBudget), longTermMemory);
    }

    /** 兼容测试：从 TokenBudget 推断 context window */
    public MemoryManager(LlmClient llmClient, int shortTermBudget, TokenBudget tokenBudget, LongTermMemory longTermMemory) {
        this(llmClient,
                ContextProfile.custom(
                        tokenBudget != null ? tokenBudget.getContextWindow() : 128_000,
                        shortTermBudget),
                longTermMemory);
    }

    private MemoryManager(LlmClient llmClient, ContextProfile contextProfile, LongTermMemory longTermMemory) {
        this.contextProfile = contextProfile;
        this.shortTermMemory = new ConversationMemory(contextProfile.shortTermMemoryBudget());
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.compressor = new ContextCompressor(llmClient);
        this.retriever = new MemoryRetriever(this.longTermMemory);
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
    }

    public void setLlmClient(LlmClient llmClient) {
        this.compressor.setLlmClient(llmClient);
        applyContextProfile(ContextProfile.from(llmClient));
    }

    public void applyContextProfile(ContextProfile contextProfile) {
        this.contextProfile = contextProfile;
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.shortTermMemory.setMaxTokens(contextProfile.shortTermMemoryBudget());
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
        recordTokenUsage(inputTokens, outputTokens, 0);
    }

    public void recordTokenUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens, cachedInputTokens);
    }

    public boolean compressIfNeeded() {
        if (!tokenBudget.needsCompression(shortTermMemory, contextProfile.compressionTriggerRatio())) {
            return false;
        }
        int beforeTokens = shortTermMemory.getTokenCount();
        log.info("上下文占用达到压缩阈值（{}%），触发短期记忆压缩",
                (int) (contextProfile.compressionTriggerRatio() * 100));
        String summary = compressor.compress(shortTermMemory);
        if (summary != null) {
            int afterTokens = shortTermMemory.getTokenCount();
            log.info("短期记忆压缩完成: {} -> {} tokens", beforeTokens, afterTokens);
        }
        return summary != null;
    }

    public void clearShortTerm() {
        shortTermMemory.clear();
    }

    public String getSystemStatus() {
        return "上下文策略: " + contextProfile.summary() + "\n"
                + shortTermMemory.getStatusSummary() + "\n"
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

    public ContextProfile getContextProfile() {
        return contextProfile;
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
