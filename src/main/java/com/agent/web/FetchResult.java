package com.agent.web;

/**
 * web_fetch 的结构化结果。
 */
public record FetchResult(
        String url,
        String title,
        String markdown,
        int contentLength,
        boolean truncated,
        boolean bodyEmpty,
        String hint
) {

    public static FetchResult ok(String url, String title, String markdown, int originalLength, boolean truncated) {
        boolean empty = markdown == null || markdown.isBlank();
        String hint = empty
                ? "未提取到正文。可能是 JS 渲染或防爬墙；本期范围内不再重试。"
                : "";
        return new FetchResult(url, title == null ? "" : title, markdown == null ? "" : markdown,
                originalLength, truncated, empty, hint);
    }
}
