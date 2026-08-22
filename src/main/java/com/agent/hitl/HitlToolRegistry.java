package com.agent.hitl;

import com.agent.policy.AuditLog;
import com.agent.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 在危险工具执行前插入 HITL 审批；关闭时行为与 ToolRegistry 一致。
 */
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler hitlHandler;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        this(hitlHandler, Path.of(System.getProperty("user.dir")));
    }

    public HitlToolRegistry(HitlHandler hitlHandler, Path workspace) {
        super(workspace);
        this.hitlHandler = hitlHandler;
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }

    @Override
    protected ToolExecutionResult executeTool(ToolInvocation invocation) {
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(invocation.name())) {
            return doExecuteTool(invocation);
        }
        if (hitlHandler.isApprovedAllByTool(invocation.name())) {
            return doExecuteTool(invocation);
        }
        return executeAfterApproval(invocation);
    }

    private ToolExecutionResult executeAfterApproval(ToolInvocation invocation) {
        long start = System.nanoTime();
        ApprovalRequest request = ApprovalRequest.of(invocation.name(), invocation.argumentsJson());
        ApprovalResult result = hitlHandler.requestApproval(request);

        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank()
                    ? result.reason()
                    : "用户拒绝了此操作";
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    invocation.name(), invocation.argumentsJson(), reason, elapsedMillis(start)));
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    "[HITL] 操作已被拒绝：" + reason,
                    elapsedMillis(start)
            );
        }

        if (result.isSkipped()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    invocation.name(), invocation.argumentsJson(), "用户跳过", elapsedMillis(start)));
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    "[HITL] 操作已被跳过",
                    elapsedMillis(start)
            );
        }

        String effectiveArgs = result.effectiveArguments(invocation.argumentsJson());
        ToolInvocation effective = new ToolInvocation(invocation.id(), invocation.name(), effectiveArgs);
        return doExecuteTool(effective);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
