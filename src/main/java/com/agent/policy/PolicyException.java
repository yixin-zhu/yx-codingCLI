package com.agent.policy;

/**
 * 策略层拒绝工具调用时抛出（路径越界等）。
 */
public class PolicyException extends RuntimeException {

    public PolicyException(String message) {
        super(message);
    }
}
