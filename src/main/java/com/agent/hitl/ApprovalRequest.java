package com.agent.hitl;

/**
 * 待审批的工具调用描述。
 */
public record ApprovalRequest(
        String toolName,
        String arguments,
        String dangerLevel,
        String riskDescription
) {
    public static ApprovalRequest of(String toolName, String arguments) {
        return new ApprovalRequest(
                toolName,
                arguments,
                ApprovalPolicy.getDangerLevel(toolName),
                ApprovalPolicy.getRiskDescription(toolName)
        );
    }

    public String toDisplayText() {
        String argsPreview = arguments == null || arguments.isBlank()
                ? "(无参数)"
                : (arguments.length() > 200 ? arguments.substring(0, 200) + "..." : arguments);
        return """
                工具: %s
                等级: %s
                风险: %s
                参数: %s
                """.formatted(toolName, dangerLevel, riskDescription, argsPreview).trim();
    }
}
