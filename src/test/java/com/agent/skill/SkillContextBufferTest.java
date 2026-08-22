package com.agent.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillContextBufferTest {

    @Test
    void drainIsOneShot() {
        SkillContextBuffer buffer = new SkillContextBuffer();
        buffer.push("web-access", "body content");

        String first = buffer.drain();
        assertTrue(first.contains("web-access"));
        assertEquals("", buffer.drain());
    }

    @Test
    void capsAtThreeSkills() {
        SkillContextBuffer buffer = new SkillContextBuffer();
        buffer.push("a", "A");
        buffer.push("b", "B");
        buffer.push("c", "C");
        buffer.push("d", "D");

        assertEquals(3, buffer.size());
        String drained = buffer.drain();
        assertFalse(drained.contains("## 已加载 Skill：a"));
        assertTrue(drained.contains("d"));
    }
}
