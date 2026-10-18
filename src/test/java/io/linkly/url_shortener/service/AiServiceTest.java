package io.linkly.url_shortener.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import io.linkly.url_shortener.exception.ApiException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
                WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        registry.add("app.gemini.timeout-seconds", () -> "1");
        registry.add("app.gemini.api-url",
                () -> wireMockServer.baseUrl() + "/mock-gemini");
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

    @AfterAll
    static void stopServer() { wireMockServer.stop(); }

    private void stubGeminiResponse(String responseText) {
        try {
            String body = new ObjectMapper().writeValueAsString(Map.of("candidates", List.of(
                    Map.of("content", Map.of("parts", List.of(Map.of("text", responseText)))))));
            wireMockServer.stubFor(post(urlPathEqualTo("/mock-gemini"))
                    .willReturn(okJson(body)));
        } catch (Exception ex) { throw new AssertionError(ex); }
    }

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
                "https://www.baeldung.com/spring-boot", "Fetched page text for safety assessment.");

        assertTrue(result);
    }

    @Test
    @DisplayName("checkIfSafe returns false when AI responds with UNSAFE")
    void checkIfSafe_unsafeUrl_returnsFalse() {
        stubGeminiResponse("UNSAFE");

        boolean result = aiService.checkIfSafe(
                "http://malware-test.com/virus.exe", "Fetched page text for safety assessment.");

        assertFalse(result);
    }

    @Test
    @DisplayName("checkIfSafe returns true when AI responds with lowercase safe")
    void checkIfSafe_lowercaseSafeResponse_returnsTrue() {
        stubGeminiResponse("safe");

        boolean result = aiService.checkIfSafe(
                "https://www.baeldung.com/spring-boot", "Fetched page text for safety assessment.");

        assertTrue(result);
    }

    @Test
    @DisplayName("checkIfSafe returns false when AI responds with verbose UNSAFE message")
    void checkIfSafe_verboseUnsafeResponse_returnsFalse() {
        stubGeminiResponse("UNSAFE - this URL contains malware");

        boolean result = aiService.checkIfSafe(
                "http://malware-test.com/virus.exe", "Fetched page text for safety assessment.");

        assertFalse(result);
    }

    // ── generateTags tests ────────────────────────────────────────────

    @Test
    @DisplayName("generateTags returns parsed tags and category")
    void generateTags_validResponse_returnsTagsAndCategory() {
        stubGeminiResponse(
                "{\"tags\": [\"java\", \"spring\"], " +
                        "\"category\": \"Technology\"}");

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
                "```json\n{\"tags\": [\"java\"], " +
                        "\"category\": \"Tech\"}\n```");

        AiService.AiTagResult result = aiService.generateTags(
                "https://www.baeldung.com/spring-boot");

        assertNotNull(result);
        assertEquals(List.of("java"), result.tags());
        assertEquals("Tech", result.category());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT SAFE", "SAFETY", "SAFE but suspicious", "UNKNOWN"})
    void ambiguousSafetyResponsesAreNeverAccepted(String text) {
        stubGeminiResponse(text);
        assertFalse(aiService.checkIfSafe("https://example.com", "Fetched page text for safety assessment."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a slug", "https://example.com", "---", "a" + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidGeneratedSlugsAreRejected(String text) {
        stubGeminiResponse(text);
        assertThrows(ApiException.class, () -> aiService.generateSlug("https://example.com"));
    }

    @Test void regenerationMustActuallyChangeTheSlug() {
        stubGeminiResponse("same-slug");
        assertThrows(ApiException.class, () -> aiService.regenerateSlug("https://example.com", "same-slug"));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 503})
    void providerErrorsReturnControlledUnavailableError(int code) {
        wireMockServer.stubFor(post(urlPathEqualTo("/mock-gemini"))
                .willReturn(aResponse().withStatus(code).withBody("provider diagnostic")));
        var ex = assertThrows(ApiException.class, () -> aiService.generateSlug("https://example.com"));
        assertEquals(503, ex.getStatus().value());
        assertFalse(ex.getMessage().contains("provider diagnostic"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"candidates\":[]}", "not json"})
    void missingAndMalformedResponsesAreHandled(String body) {
        wireMockServer.stubFor(post(urlPathEqualTo("/mock-gemini"))
                .willReturn(okJson(body)));
        assertThrows(ApiException.class, () -> aiService.generateSlug("https://example.com"));
    }

    @Test void stalledProviderIsBoundedByRequestDeadline() {
        wireMockServer.stubFor(post(urlPathEqualTo("/mock-gemini"))
                .willReturn(okJson("{}").withFixedDelay(2000)));
        assertTimeout(java.time.Duration.ofSeconds(4), () ->
                assertThrows(ApiException.class, () -> aiService.generateSlug("https://example.com")));
    }

    @Test void requestUsesJsonEncodingAndKeepsKeyOutOfQueryString() {
        stubGeminiResponse("valid-slug");
        aiService.generateSlug("https://example.com/a?x=\"quoted\"&y=\\tab");
        wireMockServer.verify(postRequestedFor(urlEqualTo("/mock-gemini"))
                .withHeader("x-goog-api-key", equalTo("test-key"))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].text")));
    }

    @Test void oversizedMetadataCannotOverflowDatabaseColumns() throws Exception {
        stubGeminiResponse(new ObjectMapper().writeValueAsString(Map.of("tags",
                List.of("a".repeat(200), "b".repeat(200), "one", "two", "three", "four", "five"),
                "category", "x".repeat(500))));
        var result = aiService.generateTags("https://example.com");
        assertTrue(String.join(",", result.tags()).length() <= 255);
        assertTrue(result.tags().size() <= 5);
        assertEquals(255, result.category().length());
    }

    @Test void missingApiKeyFailsBeforeCallingProvider() {
        org.springframework.test.util.ReflectionTestUtils.setField(aiService, "apiKey", "");
        try {
            var ex = assertThrows(ApiException.class, () -> aiService.generateSlug("https://example.com"));
            assertEquals(503, ex.getStatus().value());
            wireMockServer.verify(0, postRequestedFor(urlPathEqualTo("/mock-gemini")));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(aiService, "apiKey", "test-key");
        }
    }

    @Test void optionalTaggingFallsBackOnProviderFailure() {
        wireMockServer.stubFor(post(urlPathEqualTo("/mock-gemini")).willReturn(aResponse().withStatus(503)));
        assertEquals(new AiService.AiTagResult(List.of(), "Uncategorized"), aiService.generateTags("https://example.com"));
    }
    @Test void safetyAssessmentReceivesFetchedContentInsteadOfOnlyTheUrl() {
        stubGeminiResponse("SAFE");
        assertTrue(aiService.checkIfSafe("https://example.com", "This is the actual fetched article body."));
        wireMockServer.verify(postRequestedFor(urlEqualTo("/mock-gemini"))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].text",
                        containing("This is the actual fetched article body."))));
    }
}
