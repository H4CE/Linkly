package io.linkly.url_shortener.service;

import io.linkly.url_shortener.dto.request.ConfirmShortenRequest;
import io.linkly.url_shortener.dto.request.RegenerateSlugRequest;
import io.linkly.url_shortener.dto.request.SuggestRequest;
import io.linkly.url_shortener.dto.response.ShortenResponse;
import io.linkly.url_shortener.dto.response.SuggestResponse;
import io.linkly.url_shortener.entity.UrlMapping;
import io.linkly.url_shortener.entity.User;
import io.linkly.url_shortener.exception.UrlNotFoundException;
import io.linkly.url_shortener.repository.UrlMappingRepository;
import io.linkly.url_shortener.repository.UserRepository;
import io.linkly.url_shortener.util.Base62Encoder;
import io.linkly.url_shortener.util.SlugVerificationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UrlMappingService {

    private final UrlMappingRepository urlMappingRepository;
    private final UserRepository userRepository;
    private final Base62Encoder base62Encoder;
    private final AiService aiService;
    private final SlugVerificationUtil slugVerificationUtil;

    @Value("${app.base-url}")
    private String baseUrl;

    // ── Suggest ──────────────────────────────────────────────────────────

    public SuggestResponse suggest(SuggestRequest request) {
        String slug = aiService.generateSlug(request.getOriginalUrl());
        String token = slugVerificationUtil.generateToken(
                request.getOriginalUrl(), slug);

        return SuggestResponse.builder()
                .originalUrl(request.getOriginalUrl())
                .suggestedSlug(slug)
                .suggestedShortUrl(baseUrl + "/" + slug)
                .verificationToken(token)
                .build();
    }

    // ── regenerate ──────────────────────────────────────────────────────────
    public SuggestResponse regenerate(RegenerateSlugRequest request) {

        // Verify previous slug was actually AI generated
        boolean isValid = slugVerificationUtil.verifyToken(
                request.getOriginalUrl(),
                request.getPreviousSlug(),
                request.getVerificationToken()
        );

        if (!isValid) {
            throw new RuntimeException(
                    "Invalid verification token — previous slug was not AI generated");
        }

        String newSlug = aiService.regenerateSlug(
                request.getOriginalUrl(),
                request.getPreviousSlug()
        );

        String newToken = slugVerificationUtil.generateToken(
                request.getOriginalUrl(), newSlug);

        return SuggestResponse.builder()
                .originalUrl(request.getOriginalUrl())
                .suggestedSlug(newSlug)
                .suggestedShortUrl(baseUrl + "/" + newSlug)
                .verificationToken(newToken)
                .build();
    }

    // ── Confirm ──────────────────────────────────────────────────────────
    @CacheEvict(value = "urls", key = "#request.slug")
    @Transactional
    public ShortenResponse confirm(ConfirmShortenRequest request) {

        // Step 1 — verify slug was AI generated
        boolean isValidToken = slugVerificationUtil.verifyToken(
                request.getOriginalUrl(),
                request.getSlug(),
                request.getVerificationToken()
        );

        if (!isValidToken) {
            throw new RuntimeException(
                    "Invalid verification token — slug was not AI generated");
        }

        // Step 2 — spam check
        boolean isSafe = aiService.checkIfSafe(request.getOriginalUrl());
        if (!isSafe) {
            throw new RuntimeException("This URL has been flagged as unsafe");
        }

        // Step 3 — resolve final slug
        String finalSlug = resolveFinalSlug(request.getSlug(), request.getOriginalUrl());

        // Step 4 — generate tags
        AiService.AiTagResult tagResult = aiService.generateTags(request.getOriginalUrl());

        // Step 5 — save to DB
        User currentUser = getCurrentUser();
        UrlMapping mapping = UrlMapping.builder()
                .originalUrl(request.getOriginalUrl())
                .shortCode(finalSlug)
                .user(currentUser)
                .tags(String.join(",", tagResult.tags()))
                .category(tagResult.category())
                .build();
        mapping = urlMappingRepository.save(mapping);

        // Step 6 — build response
        return buildShortenResponse(mapping);
    }

    // ── Redirect ─────────────────────────────────────────────────────────
    @Cacheable(value = "urls", key = "#shortCode")
    @Transactional
    public String resolveShortCode(String shortCode) {
        UrlMapping mapping = urlMappingRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (mapping.getExpiresAt() != null &&
                mapping.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("This short URL has expired");
        }

        mapping.setClickCount(mapping.getClickCount() + 1);
        urlMappingRepository.save(mapping);

        return mapping.getOriginalUrl();
    }

    // ── Debug ─────────────────────────────────────────────────────────────

    public List<UrlMapping> getAllMappings() {
        return urlMappingRepository.findAll();
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private String resolveFinalSlug(String requestedSlug, String originalUrl) {
        // Slug is available — use it directly
        if (!urlMappingRepository.existsByShortCode(requestedSlug)) {
            return requestedSlug;
        }

        // Slug is taken — save temp entry to get DB id, then append Base62
        UrlMapping temp = UrlMapping.builder()
                .originalUrl(originalUrl)
                .shortCode("temp_" + System.currentTimeMillis())
                .user(getCurrentUser())
                .build();
        temp = urlMappingRepository.save(temp);

        String hybridSlug = requestedSlug + "-" + base62Encoder.encode(temp.getId());

        temp.setShortCode(hybridSlug);
        urlMappingRepository.save(temp);

        return hybridSlug;
    }

    private ShortenResponse buildShortenResponse(UrlMapping mapping) {
        List<String> tags = mapping.getTags() != null && !mapping.getTags().isEmpty()
                ? Arrays.asList(mapping.getTags().split(","))
                : List.of();

        return ShortenResponse.builder()
                .shortUrl(baseUrl + "/" + mapping.getShortCode())
                .originalUrl(mapping.getOriginalUrl())
                .createdAt(mapping.getCreatedAt())
                .expiresAt(mapping.getExpiresAt())
                .clickCount(mapping.getClickCount())
                .tags(tags)
                .category(mapping.getCategory())
                .build();
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

}