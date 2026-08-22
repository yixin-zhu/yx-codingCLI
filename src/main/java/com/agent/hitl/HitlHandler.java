package com.agent.hitl;

/**
 * HITL 审批交互接口。
 */
public interface HitlHandler {

    ApprovalResult requestApproval(ApprovalRequest request);

    boolean isEnabled();

    void setEnabled(boolean enabled);

    default boolean isApprovedAllByTool(String toolName) {
        return false;
    }

    default void clearApprovedAll() {
    }
}
