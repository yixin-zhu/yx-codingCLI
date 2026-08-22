package com.agent;

import com.agent.llm.LlmClient;
import com.agent.plan.ExecutionPlan;
import com.agent.plan.Planner;
import com.agent.plan.Task;
import com.agent.prompt.PromptAssembler;
import com.agent.prompt.PromptContext;
import com.agent.prompt.PromptMode;
import com.agent.tool.ToolRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plan-and-Execute Agent - 先规划后执行（Phase 2 MVP）
 */
public class PlanExecuteAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);
    private static final int MAX_PARALLEL_TASKS = 4;

    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    public enum PlanReviewAction {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() {
            return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
        }

        public static PlanReviewDecision supplement(String feedback) {
            return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
        }

        public static PlanReviewDecision cancel() {
            return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
        }
    }

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final PlanReviewHandler reviewHandler;
    private final PromptAssembler promptAssembler;
    private final PrintStream out;

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, null, null, (goal, plan) -> PlanReviewDecision.execute(), System.out);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, null, null, reviewHandler, System.out);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     PromptAssembler promptAssembler, PlanReviewHandler reviewHandler, PrintStream out) {
        this.llmClient = Objects.requireNonNull(llmClient);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.planner = planner != null ? planner : new Planner(llmClient, out);
        this.promptAssembler = promptAssembler != null ? promptAssembler : PromptAssembler.createDefault();
        this.reviewHandler = reviewHandler != null ? reviewHandler : (goal, plan) -> PlanReviewDecision.execute();
        this.out = out == null ? System.out : out;
        this.planner.setWorkspacePath(toolRegistry.getProjectPath());
    }

    public String run(String goal) throws IOException {
        log.info("Plan run started: {}", goal);
        ExecutionPlan plan = planner.createPlan(goal);
        return reviewAndExecutePlan(plan);
    }

    private String reviewAndExecutePlan(ExecutionPlan plan) throws IOException {
        while (true) {
            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                return executePlan(plan);
            }
            if (decision.action() == PlanReviewAction.CANCEL) {
                return "⏹️ 已取消本次计划执行。";
            }

            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isEmpty()) {
                return executePlan(plan);
            }

            out.println("📝 已收到补充要求，正在重新规划...\n");
            plan = planner.createPlan(plan.getGoal() + "\n补充要求：" + feedback);
        }
    }

    private String executePlan(ExecutionPlan plan) throws IOException {
        log.info("Executing plan: goal='{}', taskCount={}", plan.getGoal(), plan.getAllTasks().size());
        out.println("🚀 开始执行计划...\n");

        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();

        while (true) {
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }

            List<TaskExecutionResult> batchResults = executeTaskBatch(plan, executableTasks);
            for (TaskExecutionResult batchResult : batchResults) {
                Task task = batchResult.task();

                if (!batchResult.failed()) {
                    task.markCompleted(batchResult.result());
                    log.info("Task completed: {}", task.getId());
                    if (batchResult.result() == null || batchResult.result().isBlank()) {
                        out.println("✅ 完成 [" + task.getId() + "]\n");
                    } else {
                        String preview = batchResult.result().length() > 100
                                ? batchResult.result().substring(0, 100) + "..."
                                : batchResult.result();
                        out.println("✅ 完成 [" + task.getId() + "]: " + preview + "\n");
                    }
                    continue;
                }

                Exception error = batchResult.error();
                task.markFailed(error.getMessage());
                log.warn("Task failed: {} error={}", task.getId(), error.getMessage());
                out.println("❌ 失败 [" + task.getId() + "]: " + error.getMessage() + "\n");
                if (!finalResult.isEmpty()) {
                    finalResult.append("\n");
                }
                finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(error.getMessage());
            }
        }

        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";
        }

        String planSummary = finalResult.isEmpty() ? buildFinalResult(plan) : finalResult.toString();

        if (plan.hasFailed()) {
            plan.markFailed();
            if (planSummary.isBlank()) {
                return "⚠️ 计划部分完成，有任务失败。";
            }
            return "⚠️ 计划部分完成，有任务失败。\n" + planSummary;
        }

        plan.markCompleted();
        if (planSummary.isBlank()) {
            return "✅ 计划执行完成！";
        }
        return "✅ 计划执行完成！\n" + planSummary;
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private record TaskExecutionResult(Task task, String result, Exception error) {
        static TaskExecutionResult success(Task task, String result) {
            return new TaskExecutionResult(task, result, null);
        }

        static TaskExecutionResult failure(Task task, Exception error) {
            return new TaskExecutionResult(task, null, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    private List<TaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks) {
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            log.info("Executing single task: {} type={}", task.getId(), task.getType());
            out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();
            try {
                return List.of(TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task)));
            } catch (Exception e) {
                return List.of(TaskExecutionResult.failure(task, e));
            }
        }

        String parallelTaskIds = executableTasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        log.info("Executing parallel batch: {}", parallelTaskIds);
        out.println("⚡ 本轮并行执行 " + executableTasks.size() + " 个任务: " + parallelTaskIds);

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(executableTasks.size(), MAX_PARALLEL_TASKS),
                r -> {
                    Thread t = new Thread(r, "agent-plan-executor");
                    t.setDaemon(true);
                    return t;
                });
        try {
            Map<String, java.io.ByteArrayOutputStream> buffers = new LinkedHashMap<>();
            List<Future<TaskExecutionResult>> futures = new ArrayList<>();
            for (Task task : executableTasks) {
                out.println("▶️ 并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                buffers.put(task.getId(), baos);
                PrintStream taskOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
                futures.add(executor.submit(() -> {
                    try {
                        return TaskExecutionResult.success(
                                task, executeTask(plan.getGoal(), plan, task, taskOut));
                    } catch (Exception e) {
                        return TaskExecutionResult.failure(task, e);
                    }
                }));
            }

            List<TaskExecutionResult> results = new ArrayList<>();
            for (Future<TaskExecutionResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    Exception error = cause instanceof Exception exception
                            ? exception
                            : new RuntimeException(cause);
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), error));
                }
            }

            for (Task task : executableTasks) {
                java.io.ByteArrayOutputStream buf = buffers.get(task.getId());
                if (buf != null && buf.size() > 0) {
                    out.print(buf.toString(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private String executeTask(String goal, ExecutionPlan plan, Task task) throws IOException {
        return executeTask(goal, plan, task, out);
    }

    private String executeTask(String goal, ExecutionPlan plan, Task task, PrintStream taskOut) throws IOException {
        String prompt = promptAssembler.assemble(
                PromptMode.PLAN,
                PromptContext.builder()
                        .workspacePath(toolRegistry.getProjectPath())
                        .variable("taskType", task.getType().name())
                        .variable("taskDescription", task.getDescription())
                        .build()
        );

        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(prompt));
        messages.add(LlmClient.Message.user(buildTaskContext(goal, plan, task)));

        AgentBudget budget = new AgentBudget(3, 10);
        StringBuilder toolResults = new StringBuilder();

        while (true) {
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                throw new IOException(budget.describeExit(exitReason));
            }

            budget.beginIteration();
            LlmClient.ChatResponse response = llmClient.chat(messages, toolRegistry.getToolDefinitions());
            budget.recordTokens(response.inputTokens(), response.outputTokens());
            messages.add(LlmClient.Message.assistant(response.content(), response.toolCalls()));

            if (!response.hasToolCalls()) {
                if (response.content() != null && !response.content().isBlank()) {
                    return response.content();
                }
                return toolResults.toString().trim();
            }

            budget.recordToolCalls(response.toolCalls());
            List<ToolRegistry.ToolInvocation> invocations = response.toolCalls().stream()
                    .map(tc -> new ToolRegistry.ToolInvocation(tc.id(), tc.name(), tc.arguments()))
                    .toList();
            List<ToolRegistry.ToolExecutionResult> results = toolRegistry.executeTools(invocations);
            for (ToolRegistry.ToolExecutionResult result : results) {
                toolResults.append(result.result()).append("\n");
                messages.add(LlmClient.Message.tool(result.id(), result.result()));
            }
        }
    }

    private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(goal).append("\n");
        context.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus())
                        .append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append("\n");
                }
            }
        }

        context.append("请执行此任务。如果是 ANALYSIS 或 VERIFICATION 类型，请基于以上上下文直接给出结果。");
        return context.toString();
    }

    private String buildFinalResult(ExecutionPlan plan) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }
        return result.toString();
    }
}
