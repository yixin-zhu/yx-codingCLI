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
        PLAN,
        TEAM,
        HITL_STATUS,
        HITL_ON,
        HITL_OFF,
        AUDIT,
        SAVE,
        CONTEXT_STATUS,
        MEMORY_STATUS,
        MEMORY_LIST,
        MEMORY_SEARCH,
        MEMORY_DELETE,
        MEMORY_CLEAR,
        MCP_LIST,
        MCP_RESOURCES,
        SKILL_LIST,
        SKILL_SHOW,
        SKILL_ON,
        SKILL_OFF,
        SKILL_RELOAD
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

        String lower = trimmed.toLowerCase();
        if (lower.equals("/context") || lower.equals("/ctx")) {
            return new ParsedCommand(CommandType.CONTEXT_STATUS, null);
        }
        if (lower.equals("/memory") || lower.equals("/mem")) {
            return new ParsedCommand(CommandType.MEMORY_STATUS, null);
        }
        if (lower.equals("/memory clear") || lower.equals("/mem clear")) {
            return new ParsedCommand(CommandType.MEMORY_CLEAR, null);
        }
        if (lower.equals("/memory list") || lower.equals("/mem list")) {
            return new ParsedCommand(CommandType.MEMORY_LIST, null);
        }
        if (lower.startsWith("/memory delete ")) {
            return new ParsedCommand(CommandType.MEMORY_DELETE, trimmed.substring(15).trim());
        }
        if (lower.startsWith("/mem delete ")) {
            return new ParsedCommand(CommandType.MEMORY_DELETE, trimmed.substring(12).trim());
        }
        if (lower.startsWith("/memory search ")) {
            return new ParsedCommand(CommandType.MEMORY_SEARCH, trimmed.substring(15).trim());
        }
        if (lower.startsWith("/mem search ")) {
            return new ParsedCommand(CommandType.MEMORY_SEARCH, trimmed.substring(12).trim());
        }
        if (lower.equals("/save")) {
            return new ParsedCommand(CommandType.SAVE, null);
        }
        if (lower.startsWith("/save ")) {
            return new ParsedCommand(CommandType.SAVE, trimmed.substring(6).trim());
        }
        if (lower.equals("/hitl")) {
            return new ParsedCommand(CommandType.HITL_STATUS, null);
        }
        if (lower.equals("/hitl on")) {
            return new ParsedCommand(CommandType.HITL_ON, null);
        }
        if (lower.equals("/hitl off")) {
            return new ParsedCommand(CommandType.HITL_OFF, null);
        }
        if (lower.equals("/audit")) {
            return new ParsedCommand(CommandType.AUDIT, null);
        }
        if (lower.startsWith("/audit ")) {
            return new ParsedCommand(CommandType.AUDIT, trimmed.substring(7).trim());
        }
        if (lower.equals("/mcp")) {
            return new ParsedCommand(CommandType.MCP_LIST, null);
        }
        if (lower.startsWith("/mcp resources ")) {
            return new ParsedCommand(CommandType.MCP_RESOURCES, trimmed.substring(15).trim());
        }
        if (lower.equals("/skill") || lower.equals("/skill list")) {
            return new ParsedCommand(CommandType.SKILL_LIST, null);
        }
        if (lower.equals("/skill reload")) {
            return new ParsedCommand(CommandType.SKILL_RELOAD, null);
        }
        if (lower.startsWith("/skill show ")) {
            return new ParsedCommand(CommandType.SKILL_SHOW, trimmed.substring(12).trim());
        }
        if (lower.startsWith("/skill on ")) {
            return new ParsedCommand(CommandType.SKILL_ON, trimmed.substring(10).trim());
        }
        if (lower.startsWith("/skill off ")) {
            return new ParsedCommand(CommandType.SKILL_OFF, trimmed.substring(11).trim());
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
            case "/team" -> new ParsedCommand(CommandType.TEAM, payload);
            default -> trimmed.startsWith("/")
                    ? new ParsedCommand(CommandType.UNKNOWN, trimmed)
                    : ParsedCommand.none();
        };
    }
}
