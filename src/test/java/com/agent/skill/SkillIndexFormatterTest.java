package com.agent.skill;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillIndexFormatterTest {

    @Test
    void emptyListReturnsEmptyString() {
        assertEquals("", SkillIndexFormatter.format(List.of()));
    }

    @Test
    void formatsSingleSkillWithDescription() {
        Skill skill = mockSkill("web-access", "web guide", Skill.Source.BUILTIN);
        String out = SkillIndexFormatter.format(List.of(skill));
        assertTrue(out.contains("web-access"));
        assertTrue(out.contains("load_skill"));
    }

    @Test
    void capsAtTwentySkills() {
        List<Skill> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(mockSkill(String.format("skill-%02d", i), "desc " + i, Skill.Source.USER));
        }
        String out = SkillIndexFormatter.format(many);
        assertTrue(out.contains("skill-00"));
        assertTrue(out.contains("skill-19"));
    }

    private static Skill mockSkill(String name, String desc, Skill.Source source) {
        return new Skill(name, desc, "1.0.0", null, List.of(), source, "body", null, null);
    }
}
