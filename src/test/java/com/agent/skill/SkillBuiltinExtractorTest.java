package com.agent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillBuiltinExtractorTest {

    @Test
    void extractsBuiltinSkillFromClasspath(@TempDir Path tempDir) throws IOException {
        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(tempDir);
        extractor.extractAll();

        Path skillDir = tempDir.resolve("web-access");
        assertTrue(Files.isDirectory(skillDir));
        assertTrue(Files.isRegularFile(skillDir.resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(skillDir.resolve(".version")));
        assertEquals(SkillBuiltinExtractor.CURRENT_VERSION,
                Files.readString(skillDir.resolve(".version")).trim());
        assertTrue(Files.readString(skillDir.resolve("SKILL.md")).contains("web-access"));
    }

    @Test
    void skipsExtractionWhenVersionMatches(@TempDir Path tempDir) throws IOException {
        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(tempDir);
        extractor.extractAll();

        Path marker = tempDir.resolve("web-access/.user-marker");
        Files.writeString(marker, "preserved");

        extractor.extractAll();
        assertTrue(Files.exists(marker), "版本一致时不应清空缓存目录");
    }

    @Test
    void rebuildsWhenVersionMismatch(@TempDir Path tempDir) throws IOException {
        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(tempDir);
        extractor.extractAll();

        Path versionFile = tempDir.resolve("web-access/.version");
        Files.writeString(versionFile, "0.0.0-old");
        Path marker = tempDir.resolve("web-access/.user-marker");
        Files.writeString(marker, "should be wiped");

        extractor.extractAll();
        assertFalse(Files.exists(marker), "版本变化时应清空缓存目录");
        assertEquals(SkillBuiltinExtractor.CURRENT_VERSION,
                Files.readString(versionFile).trim());
    }
}
