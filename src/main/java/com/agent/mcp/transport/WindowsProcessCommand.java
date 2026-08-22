package com.agent.mcp.transport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Windows 上 Java ProcessBuilder 不能直接执行 {@code npx} 这类无扩展名命令，
 * 需要通过 {@code cmd.exe /c} 或 {@code .cmd} 后缀解析。
 */
final class WindowsProcessCommand {
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private WindowsProcessCommand() {
    }

    static List<String> buildCommandLine(String command, List<String> args) {
        List<String> line = new ArrayList<>();
        if (IS_WINDOWS && needsCmdWrapper(command)) {
            line.add("cmd.exe");
            line.add("/c");
            line.add(command);
        } else {
            line.add(command);
        }
        if (args != null) {
            line.addAll(args);
        }
        return List.copyOf(line);
    }

    static void ensureNodeOnPath(ProcessBuilder builder) {
        if (!IS_WINDOWS) {
            return;
        }
        Map<String, String> env = builder.environment();
        String pathKey = env.containsKey("Path") ? "Path" : "PATH";
        String path = env.getOrDefault(pathKey, "");
        List<String> additions = new ArrayList<>();

        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            Path nodeDir = Path.of(programFiles, "nodejs");
            if (Files.isDirectory(nodeDir) && !pathContains(path, nodeDir.toString())) {
                additions.add(nodeDir.toString());
            }
        }
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path npmDir = Path.of(appData, "npm");
            if (Files.isDirectory(npmDir) && !pathContains(path, npmDir.toString())) {
                additions.add(npmDir.toString());
            }
        }
        if (!additions.isEmpty()) {
            env.put(pathKey, String.join(";", additions) + ";" + path);
        }
    }

    private static boolean needsCmdWrapper(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        return !command.contains("\\")
                && !command.contains("/")
                && !command.contains(".");
    }

    private static boolean pathContains(String path, String segment) {
        if (path == null || path.isBlank() || segment == null || segment.isBlank()) {
            return false;
        }
        for (String part : path.split(";")) {
            if (part.equalsIgnoreCase(segment)) {
                return true;
            }
        }
        return false;
    }
}
