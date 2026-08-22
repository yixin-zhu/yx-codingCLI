package com.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * SearXNG 自托管搜索 provider（无需 API Key）。
 */
public class SearxngSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(SearxngSearchProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final OkHttpClient httpClient;

    public SearxngSearchProvider(String baseUrl) {
        this(baseUrl, new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build());
    }

    SearxngSearchProvider(String baseUrl, OkHttpClient httpClient) {
        this.baseUrl = (baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", ""));
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "searxng";
    }

    @Override
    public boolean isReady() {
        return !baseUrl.isBlank() && HttpUrl.parse(baseUrl + "/search") != null;
    }

    @Override
    public String unavailableHint() {
        return "SearXNG 未配置。请设置 SEARXNG_URL（例如 http://localhost:8888）并确保实例已启动。";
    }

    @Override
    public List<SearchResult> search(String query, int topK) throws IOException {
        if (!isReady()) {
            throw new IOException(unavailableHint());
        }
        int maxResults = topK > 0 ? Math.min(topK, 10) : 5;

        HttpUrl url = HttpUrl.parse(baseUrl + "/search").newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("language", "zh")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "agent-web-search/1.0")
                .get()
                .build();
        log.info("SearXNG search: query={}, topK={}, base={}", query, maxResults, baseUrl);

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("SearXNG 请求失败 (HTTP " + response.code() + ")");
            }
            String body = response.body() == null ? "" : response.body().string();
            return parse(body, maxResults);
        }
    }

    private List<SearchResult> parse(String json, int maxResults) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode results = root.path("results");
        List<SearchResult> out = new ArrayList<>();
        if (results.isArray()) {
            int position = 0;
            for (JsonNode node : results) {
                if (position >= maxResults) {
                    break;
                }
                String title = node.path("title").asText("");
                String link = node.path("url").asText("");
                String snippet = node.path("content").asText("");
                if (title.isBlank() && snippet.isBlank()) {
                    continue;
                }
                position++;
                out.add(SearchResult.of(position, title, link, snippet));
            }
        }
        return out;
    }
}
