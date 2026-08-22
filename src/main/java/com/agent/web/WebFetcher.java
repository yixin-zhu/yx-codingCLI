package com.agent.web;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 抓取：5MB 上限、30s 超时。
 */
public class WebFetcher {

    private static final Logger log = LoggerFactory.getLogger(WebFetcher.class);
    public static final int DEFAULT_MAX_BYTES = 5 * 1024 * 1024;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30L;

    private final OkHttpClient httpClient;
    private final int maxBytes;

    public WebFetcher() {
        this(DEFAULT_MAX_BYTES);
    }

    public WebFetcher(int maxBytes) {
        this(maxBytes, new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build());
    }

    WebFetcher(int maxBytes, OkHttpClient httpClient) {
        this.maxBytes = maxBytes;
        this.httpClient = httpClient;
    }

    public RawResponse fetch(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.9")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("User-Agent", "Mozilla/5.0 (compatible; agent-web-fetch/1.0)")
                .get()
                .build();

        log.info("web_fetch: GET {}", url);
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("响应体为空");
            }

            Charset charset = resolveCharset(body);
            byte[] bytes = readBounded(body.byteStream());
            boolean truncated = bytes.length >= maxBytes;
            String text = new String(bytes, charset);
            String contentType = response.header("Content-Type", "");
            return new RawResponse(url, text, contentType, charset.name(), truncated);
        }
    }

    private Charset resolveCharset(ResponseBody body) {
        try {
            if (body.contentType() != null && body.contentType().charset() != null) {
                return body.contentType().charset();
            }
        } catch (Exception ignored) {
        }
        return StandardCharsets.UTF_8;
    }

    private byte[] readBounded(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = input.read(buffer)) != -1) {
            int remaining = maxBytes - total;
            if (remaining <= 0) {
                break;
            }
            int writeLen = Math.min(n, remaining);
            out.write(buffer, 0, writeLen);
            total += writeLen;
            if (total >= maxBytes) {
                break;
            }
        }
        return out.toByteArray();
    }

    public record RawResponse(String url, String body, String contentType, String charset, boolean truncated) {}
}
