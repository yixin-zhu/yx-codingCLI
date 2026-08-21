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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plan-and-Execute Agent - 先规划后执行（Phase 2 MVP）
 */
public class PlanExecuteAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);

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

            for (Task task : executableTasks) {
                out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();
                try {
                    String result = executeTask(plan.getGoal(), plan, task);
                    task.markCompleted(result);
                    log.info("Task completed: {}", task.getId());
                    if (result == null || result.isBlank()) {
                        out.println("✅ 完成 [" + task.getId() + "]\n");
                    } else {
                        String preview = result.length() > 100 ? result.substring(0, 100) + "..." : result;
                        out.println("✅ 完成 [" + task.getId() + "]: " + preview + "\n");
                    }
                } catch (Exception e) {
                    task.markFailed(e.getMessage());
                    log.warn("Task failed: {} error={}", task.getId(), e.getMessage());
                    out.println("❌ 失败 [" + task.getId() + "]: " + e.getMessage() + "\n");
                    if (!finalResult.isEmpty()) {
                        finalResult.append("\n");
                    }
                    finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(e.getMessage());
                }
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

    private String executeTask(String goal, ExecutionPlan plan, Task task) throws IOException {
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
