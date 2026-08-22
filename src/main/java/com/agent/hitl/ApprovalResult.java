package com.agent.hitl;

/**
 * 用户对一次工具调用的审批决策。
 */
public record ApprovalResult(
        Decision decision,
        String modifiedArguments,
        String reason
) {
    public enum Decision {
        APPROVED,
        APPROVED_ALL,
        REJECTED,
        MODIFIED,
        SKIPPED
    }

    public static ApprovalResult approve() {
        return new ApprovalResult(Decision.APPROVED, null, null);
    }

    public static ApprovalResult approveAll() {
        return new ApprovalResult(Decision.APPROVED_ALL, null, null);
    }

    public static ApprovalResult reject(String reason) {
        return new ApprovalResult(Decision.REJECTED, null, reason);
    }

    public static ApprovalResult modify(String modifiedArguments) {
        return new ApprovalResult(Decision.MODIFIED, modifiedArguments, null);
    }

    public static ApprovalResult skip() {
        return new ApprovalResult(Decision.SKIPPED, null, null);
    }

    public boolean isApproved() {
        return decision == Decision.APPROVED
                || decision == Decision.APPROVED_ALL
                || decision == Decision.MODIFIED;
    }

    public boolean isApprovedAll() {
        return decision == Decision.APPROVED_ALL;
    }

    public boolean isRejected() {
        return decision == Decision.REJECTED;
    }

    public boolean isSkipped() {
        return decision == Decision.SKIPPED;
    }

    public String effectiveArguments(String originalArguments) {
        if (decision == Decision.MODIFIED && modifiedArguments != null && !modifiedArguments.isBlank()) {
            return modifiedArguments;
        }
        return originalArguments;
    }
}
