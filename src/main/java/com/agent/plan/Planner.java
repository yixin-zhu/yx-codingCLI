package com.agent.plan;

import com.agent.llm.LlmClient;
import com.agent.prompt.PromptAssembler;
import com.agent.prompt.PromptContext;
import com.agent.prompt.PromptMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 规划器 - 使用 LLM 将复杂任务分解为执行计划
 */
public class Planner {

    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    private final LlmClient llmClient;
    private final PrintStream out;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptAssembler promptAssembler;
    private String workspacePath = ".";

    public Planner(LlmClient llmClient) {
        this(llmClient, System.out, PromptAssembler.createDefault());
    }

    public Planner(LlmClient llmClient, PrintStream out) {
        this(llmClient, out, PromptAssembler.createDefault());
    }

    Planner(LlmClient llmClient, PrintStream out, PromptAssembler promptAssembler) {
        this.llmClient = Objects.requireNonNull(llmClient);
        this.out = out == null ? System.out : out;
        this.promptAssembler = Objects.requireNonNull(promptAssembler);
    }

    public void setWorkspacePath(String workspacePath) {
        if (workspacePath != null && !workspacePath.isBlank()) {
            this.workspacePath = workspacePath;
        }
    }

    public ExecutionPlan createPlan(String goal) throws IOException {
        out.println("📋 正在规划任务: " + goal + "\n");

        if (isSimpleGoal(goal)) {
            return createMinimalPlan(goal);
        }

        List<LlmClient.Message> messages = Arrays.asList(
                LlmClient.Message.system(promptAssembler.assemble(
                        PromptMode.PLANNER,
                        PromptContext.builder().workspacePath(workspacePath).build()
                )),
                LlmClient.Message.user("请为以下任务制定执行计划：\n" + goal)
        );

        LlmClient.ChatResponse response = llmClient.chat(messages, null);
        log.debug("Planner response length: {}", response.content() == null ? 0 : response.content().length());
        return parsePlan(goal, response.content());
    }

    ExecutionPlan parsePlan(String goal, String planJson) throws IOException {
        String cleaned = planJson == null ? "" : planJson
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        JsonNode root = mapper.readTree(cleaned);
        String summary = root.path("summary").asText();
        JsonNode tasksNode = root.path("tasks");

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summary);

        Map<String, String> idMapping = new HashMap<>();
        int taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String originalId = taskNode.path("id").asText();
            String newId = "task_" + taskIndex++;
            idMapping.put(originalId, newId);
            plan.addTask(new Task(
                    newId,
                    taskNode.path("description").asText(),
                    parseTaskType(taskNode.path("type").asText())
            ));
        }

        taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String newId = "task_" + taskIndex++;
            Task task = plan.getTask(newId);
            JsonNode depsNode = taskNode.path("dependencies");
            if (depsNode.isArray()) {
                for (JsonNode depNode : depsNode) {
                    String originalDepId = depNode.asText();
                    String newDepId = idMapping.getOrDefault(originalDepId, originalDepId);
                    Task dep = plan.getTask(newDepId);
                    if (dep != null) {
                        task.addDependency(newDepId);
                        dep.addDependent(task.getId());
                    }
                }
            }
        }

        if (!plan.computeExecutionOrder()) {
            throw new IOException("计划中存在循环依赖");
        }
        return plan;
    }

    private Task.TaskType parseTaskType(String typeStr) {
        return switch (typeStr.toUpperCase(Locale.ROOT)) {
            case "FILE_READ" -> Task.TaskType.FILE_READ;
            case "FILE_WRITE" -> Task.TaskType.FILE_WRITE;
            case "COMMAND" -> Task.TaskType.COMMAND;
            case "VERIFICATION" -> Task.TaskType.VERIFICATION;
            default -> Task.TaskType.ANALYSIS;
        };
    }

    private String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    private boolean isSimpleGoal(String goal) {
        if (goal == null) {
            return false;
        }
        String normalized = goal.trim();
        if (normalized.isEmpty()) {
            return false;
        }

        boolean hasMultiStepCue = normalized.contains("然后")
                || normalized.contains("并且")
                || normalized.contains("并")
                || normalized.contains("再")
                || normalized.contains("最后")
                || normalized.contains("同时")
                || normalized.contains("先")
                || normalized.contains("之后")
                || normalized.contains("接着")
                || normalized.contains("以及");
        if (hasMultiStepCue) {
            return false;
        }
        if (normalized.length() > 30) {
            return false;
        }

        return normalized.contains("列出")
                || normalized.contains("查看")
                || normalized.contains("读取")
                || normalized.contains("显示")
                || normalized.contains("执行")
                || normalized.contains("运行")
                || normalized.contains("搜索")
                || normalized.contains("当前目录")
                || normalized.contains("文件");
    }

    private ExecutionPlan createMinimalPlan(String goal) {
        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary("直接执行简单任务：" + goal.trim());
        plan.addTask(new Task("task_1", goal.trim(), inferSimpleTaskType(goal)));
        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("简单计划不应出现循环依赖");
        }
        return plan;
    }

    private Task.TaskType inferSimpleTaskType(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.contains("读取") || normalized.contains("打开")
                || normalized.contains("查看") && normalized.contains("文件")) {
            return Task.TaskType.FILE_READ;
        }
        if (normalized.contains("写入") || normalized.contains("修改") || normalized.contains("创建文件")) {
            return Task.TaskType.FILE_WRITE;
        }
        if (normalized.contains("分析") || normalized.contains("总结") || normalized.contains("解释")) {
            return Task.TaskType.ANALYSIS;
        }
        if (normalized.contains("验证") || normalized.contains("检查")) {
            return Task.TaskType.VERIFICATION;
        }
        return Task.TaskType.COMMAND;
    }
}
