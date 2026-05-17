package io.linkly.url_shortener.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiService {

    @Value("${app.gemini.api-key}")
    private String apiKey;

    @Value("${app.gemini.api-url}")
    private String apiUrl;

    @Value("${app.gemini.max-slug-length}")
    private int maxSlugLength;

    @Value("${app.gemini.max-tags}")
    private int maxTags;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

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
        return callGemini(prompt).trim().toLowerCase();
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
        return callGemini(prompt).trim().toLowerCase();
    }

    public boolean checkIfSafe(String originalUrl) {
        String prompt = String.format(
                "Is this URL safe or potentially malicious/spam?: %s\n" +
                        "Rules:\n" +
                        "- Respond with ONLY one word: SAFE or UNSAFE\n" +
                        "- Consider phishing, malware, adult content as UNSAFE\n" +
                        "- When in doubt, respond SAFE",
                originalUrl
        );
        String response = callGemini(prompt).trim().toUpperCase();
        return response.contains("SAFE") && !response.contains("UNSAFE");
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
            root.path("tags").forEach(tag -> tags.add(tag.asText()));
            String category = root.path("category").asText("Uncategorized");
            return new AiTagResult(tags, category);

        } catch (Exception e) {
            return new AiTagResult(List.of(), "Uncategorized");
        }
    }

    private String callGemini(String prompt) {
        try {
            String requestBody = String.format("""
                    {
                        "contents": [
                            {
                                "parts": [
                                    {
                                        "text": "%s"
                                    }
                                ]
                            }
                        ]
                    }
                    """, escapeJson(prompt));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "?key=" + apiKey))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            JsonNode root = objectMapper.readTree(response.body());
            return root
                    .path("candidates")
                    .get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();

        } catch (Exception e) {
            throw new RuntimeException("Gemini API call failed: " + e.getMessage());
        }
    }

    private String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public record AiTagResult(List<String> tags, String category) {}

}