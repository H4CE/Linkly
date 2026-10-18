package io.linkly.url_shortener.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.linkly.url_shortener.exception.ApiException;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AiService {

    @Value("${app.gemini.api-key:}")
    private String apiKey;

    @Value("${app.gemini.api-url}")
    private String apiUrl;

    @Value("${app.gemini.max-slug-length}")
    private int maxSlugLength;

    @Value("${app.gemini.max-tags}")
    private int maxTags;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${app.gemini.timeout-seconds:15}")
    private int timeoutSeconds;

    public String generateSlug(String originalUrl) {
        String prompt = String.format(
                "Generate a short, URL-friendly slug for this URL: %s\n" +
                        "Rules:\n" +
                        "- Maximum %d characters\n" +
                        "- Only lowercase letters, numbers and hyphens\n" +
                        "- No special characters or spaces\n" +
                        "- Must be descriptive and meaningful\n" +
                        "- Return ONLY the slug, nothing else, no explanation",
                originalUrl, maxSlugLength
        );
        return validateSlug(callGemini(prompt));
    }

    public String regenerateSlug(String originalUrl, String previousSlug) {
        String prompt = String.format(
                "Generate a short, URL-friendly slug for this URL: %s\n" +
                        "Rules:\n" +
                        "- Maximum %d characters\n" +
                        "- Only lowercase letters, numbers and hyphens\n" +
                        "- No special characters or spaces\n" +
                        "- Must be descriptive and meaningful\n" +
                        "- Do NOT suggest this slug: %s\n" +
                        "- Return ONLY the slug, nothing else, no explanation",
                originalUrl, maxSlugLength, previousSlug
        );
        String slug = validateSlug(callGemini(prompt));
        if (slug.equalsIgnoreCase(previousSlug)) {
            throw unavailable();
        }
        return slug;
    }

    public boolean checkIfSafe(String originalUrl) {
        String prompt = String.format(
                "Is this URL safe or potentially malicious/spam?: %s\n" +
                        "Rules:\n" +
                        "- Respond with ONLY one word: SAFE or UNSAFE\n" +
                        "- Consider phishing, malware, adult content as UNSAFE\n" +
                        "- When in doubt, respond UNSAFE",
                originalUrl
        );
        // Substring matching incorrectly accepted responses such as 'NOT SAFE'.
        return "SAFE".equalsIgnoreCase(callGemini(prompt).trim());
    }

    public AiTagResult generateTags(String originalUrl) {
        String prompt = String.format(
                "Generate tags and a category for this URL: %s\n" +
                        "Rules:\n" +
                        "- Return ONLY valid JSON, no markdown, no explanation\n" +
                        "- Maximum %d tags\n" +
                        "- Format exactly like this:\n" +
                        "{\"tags\": [\"tag1\", \"tag2\"], \"category\": \"CategoryName\"}",
                originalUrl, maxTags
        );

        try {
            String response = callGemini(prompt).trim();
            response = response
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();

            JsonNode root = objectMapper.readTree(response);
            List<String> tags = new ArrayList<>();
            // AI output is untrusted: enforce the actual VARCHAR(255) storage limits.
            if (root.path("tags").isArray()) {
                for (JsonNode tag : root.path("tags")) {
                    if (tags.size() >= maxTags) break;
                    if (!tag.isTextual()) continue;
                    String value = tag.asText().trim().replace(",", " ");
                    if (!value.isEmpty() && String.join(",", tags).length() + value.length()
                            + (tags.isEmpty() ? 0 : 1) <= 255) tags.add(value);
                }
            }
            String category = root.path("category").isTextual()
                    ? root.path("category").asText().trim() : "Uncategorized";
            if (category.isEmpty()) category = "Uncategorized";
            if (category.length() > 255) category = category.substring(0, 255);
            return new AiTagResult(tags, category);

        } catch (Exception e) {
            return new AiTagResult(List.of(), "Uncategorized");
        }
    }

    private String callGemini(String prompt) {
        if (apiKey == null || apiKey.isBlank()) throw unavailable();
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of("contents",
                    List.of(Map.of("parts", List.of(Map.of("text", prompt))))));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) throw unavailable();
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (!text.isTextual() || text.asText().isBlank()) throw unavailable();
            return text.asText();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception ex) {
            // Do not expose provider response bodies or credentials in API errors.
            throw unavailable();
        }
    }

    private String validateSlug(String text) {
        String slug = text.trim().toLowerCase(Locale.ROOT);
        if (slug.length() > Math.min(maxSlugLength, 100) || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw unavailable();
        }
        return slug;
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI service unavailable or returned an invalid response");
    }

    public record AiTagResult(List<String> tags, String category) {}

}
