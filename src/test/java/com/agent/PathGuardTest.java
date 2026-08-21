package com.agent;

import com.agent.policy.PathGuard;
import com.agent.policy.PolicyException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathGuardTest {

    @Test
    void resolvesRelativePathInsideRoot(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("a.txt"), "ok");
        PathGuard guard = new PathGuard(root.toString());

        Path safe = guard.resolveSafe("a.txt");
        assertEquals(root.resolve("a.txt").toAbsolutePath().normalize(), safe.toAbsolutePath().normalize());
    }

    @Test
    void rejectsParentTraversal(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        PolicyException ex = assertThrows(PolicyException.class, () -> guard.resolveSafe("../secret.txt"));
        assertTrue(ex.getMessage().contains("路径越界"));
    }

    @Test
    void rejectsBlankPath(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        assertThrows(PolicyException.class, () -> guard.resolveSafe("   "));
    }
}
