package com.agent.mcp.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WindowsProcessCommandTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void wrapsBareCommandWithCmdExeOnWindows() {
        List<String> line = WindowsProcessCommand.buildCommandLine("npx", List.of("-y", "pkg"));
        assertEquals(List.of("cmd.exe", "/c", "npx", "-y", "pkg"), line);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void keepsAbsoluteExecutableUnchangedOnWindows() {
        List<String> line = WindowsProcessCommand.buildCommandLine(
                "C:\\Program Files\\nodejs\\npx.cmd", List.of("-v"));
        assertEquals(List.of("C:\\Program Files\\nodejs\\npx.cmd", "-v"), line);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void keepsBareCommandOnUnix() {
        List<String> line = WindowsProcessCommand.buildCommandLine("npx", List.of("-y", "pkg"));
        assertEquals(List.of("npx", "-y", "pkg"), line);
    }
}
