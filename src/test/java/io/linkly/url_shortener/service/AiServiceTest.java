package io.linkly.url_shortener.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AiServiceTest {

    @Autowired
    private AiService aiService;

    private static WireMockServer wireMockServer;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        wireMockServer = new WireMockServer(
                WireMockConfiguration.wireMockConfig().port(9999));
        wireMockServer.start();
        registry.add("app.gemini.api-url",
                () -> "http://localhost:9999/mock-gemini");
    }

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.resetAll();
    }

    // ── Helper ────────────────────────────────────────────────────────

    private void stubGeminiResponse(String responseText) {
        wireMockServer.stubFor(
                post(urlPathMatching("/mock-gemini.*"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(String.format("""
                                        {
                                            "candidates": [{
                                                "content": {
                                                    "parts": [{"text": "%s"}]
                                                }
                                            }]
                                        }
                                        """, responseText))));
    }

    // ── generateSlug tests ────────────────────────────────────────────

    @Test
    @DisplayName("generateSlug returns trimmed lowercase slug from AI response")
    void generateSlug_validUrl_returnsCleanSlug() {
        stubGeminiResponse("spring-boot-guide");

        String slug = aiService.generateSlug(
                "https://www.baeldung.com/spring-boot");

        assertEquals("spring-boot-guide", slug);
    }

    @Test
    @DisplayName("generateSlug trims whitespace from AI response")
    void generateSlug_responseWithWhitespace_returnsTrimmedSlug() {
        stubGeminiResponse("  spring-boot-guide  ");

        String slug = aiService.generateSlug(
                "https://www.baeldung.com/spring-boot");

        assertEquals("spring-boot-guide", slug);
    }

    @Test
    @DisplayName("generateSlug converts uppercase to lowercase")
    void generateSlug_uppercaseResponse_returnsLowercase() {
        stubGeminiResponse("Spring-Boot-Guide");

        String slug = aiService.generateSlug(
                "https://www.baeldung.com/spring-boot");

        assertEquals("spring-boot-guide", slug);
    }

    // ── regenerateSlug tests ──────────────────────────────────────────

    @Test
    @DisplayName("regenerateSlug returns different slug than previous")
    void regenerateSlug_validInputs_returnsNewSlug() {
        stubGeminiResponse("baeldung-spring-tutorial");

        String newSlug = aiService.regenerateSlug(
                "https://www.baeldung.com/spring-boot",
                "spring-boot-guide");

        assertEquals("baeldung-spring-tutorial", newSlug);
        assertNotEquals("spring-boot-guide", newSlug);
    }

    // ── checkIfSafe tests ─────────────────────────────────────────────

    @Test
    @DisplayName("checkIfSafe returns true when AI responds with SAFE")
    void checkIfSafe_safeUrl_returnsTrue() {
        stubGeminiResponse("SAFE");

        boolean result = aiService.checkIfSafe(
                "https://www.baeldung.com/spring-boot");

        assertTrue(result);
    }

    @Test
    @DisplayName("checkIfSafe returns false when AI responds with UNSAFE")
    void checkIfSafe_unsafeUrl_returnsFalse() {
        stubGeminiResponse("UNSAFE");

        boolean result = aiService.checkIfSafe(
                "http://malware-test.com/virus.exe");

        assertFalse(result);
    }

    @Test
    @DisplayName("checkIfSafe returns true when AI responds with lowercase safe")
    void checkIfSafe_lowercaseSafeResponse_returnsTrue() {
        stubGeminiResponse("safe");

        boolean result = aiService.checkIfSafe(
                "https://www.baeldung.com/spring-boot");

        assertTrue(result);
    }

    @Test
    @DisplayName("checkIfSafe returns false when AI responds with verbose UNSAFE message")
    void checkIfSafe_verboseUnsafeResponse_returnsFalse() {
        stubGeminiResponse("UNSAFE - this URL contains malware");

        boolean result = aiService.checkIfSafe(
                "http://malware-test.com/virus.exe");

        assertFalse(result);
    }

    // ── generateTags tests ────────────────────────────────────────────

    @Test
    @DisplayName("generateTags returns parsed tags and category")
    void generateTags_validResponse_returnsTagsAndCategory() {
        stubGeminiResponse(
                "{\\\"tags\\\": [\\\"java\\\", \\\"spring\\\"], " +
                        "\\\"category\\\": \\\"Technology\\\"}");

        AiService.AiTagResult result = aiService.generateTags(
                "https://www.baeldung.com/spring-boot");

        assertNotNull(result);
        assertFalse(result.tags().isEmpty());
        assertNotNull(result.category());
    }

    @Test
    @DisplayName("generateTags returns empty tags on malformed AI response")
    void generateTags_malformedResponse_returnsEmptyTags() {
        stubGeminiResponse("this is not valid json at all");

        AiService.AiTagResult result = aiService.generateTags(
                "https://www.baeldung.com/spring-boot");

        assertNotNull(result);
        assertEquals(List.of(), result.tags());
        assertEquals("Uncategorized", result.category());
    }

    @Test
    @DisplayName("generateTags handles markdown wrapped JSON from AI")
    void generateTags_markdownWrappedJson_returnsTagsAndCategory() {
        stubGeminiResponse(
                "```json\\n{\\\"tags\\\": [\\\"java\\\"], " +
                        "\\\"category\\\": \\\"Tech\\\"}\\n```");

        AiService.AiTagResult result = aiService.generateTags(
                "https://www.baeldung.com/spring-boot");

        assertNotNull(result);
        assertNotNull(result.category());
    }

}