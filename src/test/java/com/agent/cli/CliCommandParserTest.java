package com.agent.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliCommandParserTest {

    @Test
    void parsesKnownCommands() {
        assertEquals(CliCommandParser.CommandType.EXIT, CliCommandParser.parse("/exit").type());
        assertEquals(CliCommandParser.CommandType.RESET, CliCommandParser.parse("/reset").type());
        assertEquals(CliCommandParser.CommandType.HELP, CliCommandParser.parse("/help").type());
        assertEquals(CliCommandParser.CommandType.PWD, CliCommandParser.parse("/pwd").type());
        assertEquals(CliCommandParser.CommandType.PLAN, CliCommandParser.parse("/plan demo").type());
        assertEquals(CliCommandParser.CommandType.TEAM, CliCommandParser.parse("/team demo").type());
        assertEquals(CliCommandParser.CommandType.HITL_ON, CliCommandParser.parse("/hitl on").type());
        assertEquals(CliCommandParser.CommandType.AUDIT, CliCommandParser.parse("/audit 5").type());
        assertEquals(CliCommandParser.CommandType.SAVE, CliCommandParser.parse("/save fact").type());
        assertEquals(CliCommandParser.CommandType.MEMORY_LIST, CliCommandParser.parse("/memory list").type());
        assertEquals(CliCommandParser.CommandType.MCP_LIST, CliCommandParser.parse("/mcp").type());
        assertEquals(CliCommandParser.CommandType.MCP_RESOURCES, CliCommandParser.parse("/mcp resources echo").type());
    }

    @Test
    void unknownSlashCommandMarkedUnknown() {
        assertEquals(CliCommandParser.CommandType.UNKNOWN, CliCommandParser.parse("/foo").type());
    }

    @Test
    void plainTextIsNone() {
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("hello").type());
    }
}
