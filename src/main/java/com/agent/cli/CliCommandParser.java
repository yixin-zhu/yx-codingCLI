package com.agent.cli;

/**
 * 斜杠命令解析器：只负责识别命令类型，不负责执行。
 */
public final class CliCommandParser {

    public enum CommandType {
        NONE,
        UNKNOWN,
        EXIT,
        RESET,
        HELP,
        HISTORY,
        TOOLS,
        SYSTEM,
        PWD,
        PLAN
    }

    public record ParsedCommand(CommandType type, String payload) {
        public static ParsedCommand none() {
            return new ParsedCommand(CommandType.NONE, null);
        }
    }

    private CliCommandParser() {
    }

    public static ParsedCommand parse(String input) {
        if (input == null) {
            return ParsedCommand.none();
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return ParsedCommand.none();
        }

        String[] parts = trimmed.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String payload = parts.length > 1 ? parts[1] : null;

        return switch (command) {
            case "/exit", "/quit", "/q" -> new ParsedCommand(CommandType.EXIT, payload);
            case "/reset" -> new ParsedCommand(CommandType.RESET, payload);
            case "/help", "/?" -> new ParsedCommand(CommandType.HELP, payload);
            case "/history" -> new ParsedCommand(CommandType.HISTORY, payload);
            case "/tools" -> new ParsedCommand(CommandType.TOOLS, payload);
            case "/system" -> new ParsedCommand(CommandType.SYSTEM, payload);
            case "/pwd" -> new ParsedCommand(CommandType.PWD, payload);
            case "/plan" -> new ParsedCommand(CommandType.PLAN, payload);
            default -> trimmed.startsWith("/")
                    ? new ParsedCommand(CommandType.UNKNOWN, trimmed)
                    : ParsedCommand.none();
        };
    }
}
