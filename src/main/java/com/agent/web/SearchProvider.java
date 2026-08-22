package com.agent.web;

import java.io.IOException;
import java.util.List;

/**
 * 搜索引擎抽象。
 */
public interface SearchProvider {

    String name();

    boolean isReady();

    String unavailableHint();

    List<SearchResult> search(String query, int topK) throws IOException;
}
