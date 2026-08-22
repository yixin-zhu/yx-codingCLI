package com.agent.web;

/**
 * 一条搜索结果。
 */
public record SearchResult(int position, String title, String url, String snippet, String source) {

    public static SearchResult of(int position, String title, String url, String snippet) {
        return new SearchResult(position, safe(title), safe(url), safe(snippet), extractHost(url));
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }
}
