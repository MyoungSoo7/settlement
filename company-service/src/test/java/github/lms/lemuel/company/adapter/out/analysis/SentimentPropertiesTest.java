package github.lms.lemuel.company.adapter.out.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentimentPropertiesTest {

    @Test
    @DisplayName("Claude — 빈/누락 값은 기본값으로 채우고 configured=false")
    void claudeDefaults() {
        ClaudeSentimentProperties props = new ClaudeSentimentProperties(null, "", "", "", 0);

        assertEquals("claude-opus-4-8", props.model());
        assertEquals("https://api.anthropic.com", props.baseUrl());
        assertEquals("2023-06-01", props.version());
        assertEquals(20, props.maxTokens());
        assertEquals("", props.apiKey());
        assertFalse(props.configured());
    }

    @Test
    @DisplayName("Claude — 명시 값은 그대로 유지하고 키가 있으면 configured=true")
    void claudeExplicit() {
        ClaudeSentimentProperties props = new ClaudeSentimentProperties(
                "sk-key", "claude-sonnet-5", "https://proxy.local", "2024-01-01", 64);

        assertEquals("sk-key", props.apiKey());
        assertEquals("claude-sonnet-5", props.model());
        assertEquals("https://proxy.local", props.baseUrl());
        assertEquals("2024-01-01", props.version());
        assertEquals(64, props.maxTokens());
        assertTrue(props.configured());
    }

    @Test
    @DisplayName("Gemini — 빈/누락 값은 기본값으로 채우고 configured=false (상한 200·간격 0)")
    void geminiDefaults() {
        GeminiSentimentProperties props = new GeminiSentimentProperties(null, "", "", 0, 0, -5);

        assertEquals("gemini-2.5-flash", props.model());
        assertEquals("https://generativelanguage.googleapis.com", props.baseUrl());
        assertEquals(256, props.maxTokens());
        assertEquals("", props.apiKey());
        assertFalse(props.configured());
        assertEquals(200, props.dailyQuota());   // <=0 → 기본 200
        assertEquals(0, props.minIntervalMs());   // 음수 → 0
    }

    @Test
    @DisplayName("Gemini — 명시 값은 그대로 유지하고 키가 있으면 configured=true")
    void geminiExplicit() {
        GeminiSentimentProperties props = new GeminiSentimentProperties(
                "goog-key", "gemini-2.5-pro", "https://proxy.local", 128, 50, 1500);

        assertEquals("goog-key", props.apiKey());
        assertEquals("gemini-2.5-pro", props.model());
        assertEquals("https://proxy.local", props.baseUrl());
        assertEquals(128, props.maxTokens());
        assertTrue(props.configured());
        assertEquals(50, props.dailyQuota());
        assertEquals(1500, props.minIntervalMs());
    }
}
