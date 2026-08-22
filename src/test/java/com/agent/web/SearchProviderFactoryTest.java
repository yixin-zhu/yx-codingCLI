package com.agent.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchProviderFactoryTest {

    @Test
    void prefersExplicitProvider() {
        assertEquals("searxng", SearchProviderFactory.pickProvider("searxng", "", ""));
        assertEquals("serpapi", SearchProviderFactory.pickProvider("serpapi", "", "http://localhost:8888"));
    }

    @Test
    void autoPicksSerpApiWhenKeyPresent() {
        assertEquals("serpapi", SearchProviderFactory.pickProvider(null, "key-123", "http://localhost:8888"));
    }

    @Test
    void autoPicksSearxngWhenOnlyUrlPresent() {
        assertEquals("searxng", SearchProviderFactory.pickProvider(null, null, "http://localhost:8888"));
    }

    @Test
    void defaultsToSerpApiPlaceholder() {
        assertEquals("serpapi", SearchProviderFactory.pickProvider(null, null, null));
    }
}
