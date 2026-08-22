package com.agent.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * 按环境变量选择 SearchProvider：serpapi 或 searxng。
 */
public final class SearchProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(SearchProviderFactory.class);

    private SearchProviderFactory() {}

    public static SearchProvider create() {
        String provider = readEnv("SEARCH_PROVIDER");
        String serpKey = readEnv("SERPAPI_KEY");
        String searxngUrl = readEnv("SEARXNG_URL");

        String chosen = pickProvider(provider, serpKey, searxngUrl);
        log.info("SearchProvider chosen: {}", chosen);

        return switch (chosen) {
            case "searxng" -> new SearxngSearchProvider(searxngUrl);
            default -> new SerpApiSearchProvider(serpKey);
        };
    }

    static String pickProvider(String explicit, String serpKey, String searxngUrl) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        if (serpKey != null && !serpKey.isBlank()) {
            return "serpapi";
        }
        if (searxngUrl != null && !searxngUrl.isBlank()) {
            return "searxng";
        }
        return "serpapi";
    }

    private static String readEnv(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromProp = System.getProperty(key);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        return readFromDotEnv(key);
    }

    private static String readFromDotEnv(String key) {
        File[] envFiles = {new File(".env"), new File(System.getProperty("user.home"), ".env")};
        for (File envFile : envFiles) {
            if (!envFile.exists()) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    if (line.startsWith(key + "=")) {
                        return line.substring((key + "=").length()).trim();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
