package com.agent.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量检索分词：英文按词切分，中文提取连续汉字片段及 2-gram。
 */
final class MemoryQueryTokenizer {

    private static final Pattern WORD_PATTERN = Pattern.compile("[a-z0-9]{2,}");
    private static final Pattern HAN_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]{2,}");

    private MemoryQueryTokenizer() {
    }

    static Set<String> tokenize(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }

        String normalized = query.toLowerCase(Locale.ROOT).trim();
        Matcher wordMatcher = WORD_PATTERN.matcher(normalized);
        while (wordMatcher.find()) {
            tokens.add(wordMatcher.group());
        }

        Matcher hanMatcher = HAN_PATTERN.matcher(normalized);
        while (hanMatcher.find()) {
            String segment = hanMatcher.group();
            tokens.add(segment);
            if (segment.length() >= 3) {
                for (int i = 0; i < segment.length() - 1; i++) {
                    tokens.add(segment.substring(i, i + 2));
                }
            }
        }

        for (String part : normalized.split("\\s+")) {
            if (part.length() >= 2) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    static boolean matches(String text, Set<String> queryTokens) {
        if (text == null || text.isBlank() || queryTokens.isEmpty()) {
            return false;
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        for (String token : queryTokens) {
            if (normalizedText.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
