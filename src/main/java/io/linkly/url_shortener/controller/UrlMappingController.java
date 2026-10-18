package io.linkly.url_shortener.controller;

import io.linkly.url_shortener.dto.request.ConfirmShortenRequest;
import io.linkly.url_shortener.dto.request.BasicShortenRequest;
import io.linkly.url_shortener.dto.request.RegenerateSlugRequest;
import io.linkly.url_shortener.dto.request.SuggestRequest;
import io.linkly.url_shortener.dto.response.ShortenResponse;
import io.linkly.url_shortener.dto.response.SuggestResponse;
import io.linkly.url_shortener.entity.UrlMapping;
import io.linkly.url_shortener.repository.UserRepository;
import io.linkly.url_shortener.service.RateLimitService;
import io.linkly.url_shortener.service.UrlMappingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/urls")
@Tag(name = "URL Management", description = "Endpoints for shortening, suggesting and redirecting URLs")
public class UrlMappingController {

    private final UrlMappingService urlMappingService;
    private final RateLimitService rateLimitService;
    private final UserRepository userRepository;

    @Value("${app.base-url}")
    private String baseUrl;

    private Long getCurrentUserId() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"))
                .getId();
    }

    @Operation(summary = "Shorten a URL without AI",
            description = "Creates a random short code without fetching the destination or assessing spam/safety. " +
                    "Requires no Gemini key, slug suggestion, or verification token. " +
                    "Only URL syntax is validated; the destination is unchecked.")
    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shortenBasic(@Valid @RequestBody BasicShortenRequest request) {
        if (!rateLimitService.isShortenAllowed(getCurrentUserId())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(urlMappingService.shortenBasic(request));
    }

    @Operation(
            summary = "List the current user's saved URLs",
            description = "Returns only the authenticated user's URL mappings, newest first, " +
                    "including the full original URL, short URL and metadata."
    )
    @GetMapping
    public ResponseEntity<List<ShortenResponse>> listMyUrls() {
        return ResponseEntity.ok(urlMappingService.getCurrentUserMappings());
    }

    @Operation(
            summary = "Suggest a short URL slug",
            description = "Uses Google Gemini AI to generate a meaningful slug for the given URL. " +
                    "Returns a suggested short URL and a verification token. " +
                    "Does NOT save anything to the database."
    )
    @PostMapping("/suggest")
    public ResponseEntity<SuggestResponse> suggest(
            @Valid @RequestBody SuggestRequest request) {

        Long userId = getCurrentUserId();
        if (!rateLimitService.isSuggestAllowed(userId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        SuggestResponse response = urlMappingService.suggest(request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Regenerate a slug suggestion",
            description = "Generates a new AI slug suggestion different from the previous one. " +
                    "Requires the verification token from the previous suggest or regenerate call. " +
                    "Does NOT save anything to the database."
    )
    @PostMapping("/regenerate")
    public ResponseEntity<SuggestResponse> regenerate(
            @Valid @RequestBody RegenerateSlugRequest request) {

        Long userId = getCurrentUserId();
        if (!rateLimitService.isRegenerateAllowed(userId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        SuggestResponse response = urlMappingService.regenerate(request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Confirm and save a short URL",
            description = "Verifies the slug was AI generated via HMAC token, " +
                    "asks Gemini to assess downloaded page content, generates tags and category, " +
                    "then saves the URL mapping to the database."
    )
    @PostMapping("/confirm")
    public ResponseEntity<ShortenResponse> confirm(@Valid @RequestBody ConfirmShortenRequest request) {
        // Confirmation calls Gemini too; a signed slug must not bypass the AI quota.
        if (!rateLimitService.isConfirmAllowed(getCurrentUserId())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        ShortenResponse response = urlMappingService.confirm(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Redirect to original URL",
            description = "Looks up the short code in the database (Redis cached), " +
                    "increments the click counter, and redirects to the original URL with HTTP 302."
    )
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String originalUrl = urlMappingService.resolveShortCode(shortCode);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, originalUrl);
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }
}
