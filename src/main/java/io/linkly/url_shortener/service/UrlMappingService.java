package io.linkly.url_shortener.service;

import io.linkly.url_shortener.dto.request.ConfirmShortenRequest;
import io.linkly.url_shortener.dto.request.BasicShortenRequest;
import io.linkly.url_shortener.dto.request.RegenerateSlugRequest;
import io.linkly.url_shortener.dto.request.SuggestRequest;
import io.linkly.url_shortener.dto.response.ShortenResponse;
import io.linkly.url_shortener.dto.response.SuggestResponse;
import io.linkly.url_shortener.entity.UrlMapping;
import io.linkly.url_shortener.entity.User;
import io.linkly.url_shortener.exception.UrlNotFoundException;
import io.linkly.url_shortener.exception.ApiException;
import io.linkly.url_shortener.repository.UrlMappingRepository;
import io.linkly.url_shortener.repository.UserRepository;
import io.linkly.url_shortener.util.SlugVerificationUtil;
import io.linkly.url_shortener.util.RandomShortCodeGenerator;
import io.linkly.url_shortener.safety.PageContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
    private final UrlMappingCreationService urlMappingCreationService;
    private final UrlLookupService urlLookupService;
    private final AiService aiService;
    private final SlugVerificationUtil slugVerificationUtil;
    private final PageContentService pageContentService;
    private final RandomShortCodeGenerator randomShortCodeGenerator;

    @Value("${app.base-url}")
    private String baseUrl;

    public ShortenResponse shortenBasic(BasicShortenRequest request) {
        // Basic shortening only validates URL syntax, then saves it. It neither downloads
        // the destination nor calls Gemini, so this route provides no spam/safety assessment.
        UrlMapping mapping = urlMappingCreationService.createMapping(request.getOriginalUrl(),
                randomShortCodeGenerator.generate(), getCurrentUser(), "", "Uncategorized");
        return buildShortenResponse(mapping);
    }

    @Transactional(readOnly = true)
    public List<ShortenResponse> getCurrentUserMappings() {
        User currentUser = getCurrentUser();
        return urlMappingRepository
                .findAllByUserIdOrderByCreatedAtDescIdDesc(currentUser.getId())
                .stream()
                .map(this::buildShortenResponse)
                .toList();
    }

    // ── Suggest ──────────────────────────────────────────────────────────

    public SuggestResponse suggest(SuggestRequest request) {
        String slug = aiService.generateSlug(request.getOriginalUrl());
        String token = slugVerificationUtil.generateToken(
                request.getOriginalUrl(), slug);

        return SuggestResponse.builder()
                .originalUrl(request.getOriginalUrl())
                .suggestedSlug(slug)
                .suggestedShortUrl(buildShortUrl(slug))
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
            throw new ApiException(HttpStatus.BAD_REQUEST,
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
                .suggestedShortUrl(buildShortUrl(newSlug))
                .verificationToken(newToken)
                .build();
    }

    // ── Confirm ──────────────────────────────────────────────────────────
    public ShortenResponse confirm(ConfirmShortenRequest request) {

        // Step 1 — verify slug was AI generated
        boolean isValidToken = slugVerificationUtil.verifyToken(
                request.getOriginalUrl(),
                request.getSlug(),
                request.getVerificationToken()
        );

        if (!isValidToken) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Invalid verification token — slug was not AI generated");
        }

        // Java fetches/prepares content; Gemini alone decides whether it is acceptable.
        String pageContent = pageContentService.fetchForAi(request.getOriginalUrl());
        boolean isSafe = aiService.checkIfSafe(request.getOriginalUrl(), pageContent);
        if (!isSafe) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This URL has been flagged as unsafe");
        }

        // Finish the external AI calls before opening a database creation transaction.
        // This also avoids repeating paid AI calls when a slug collision needs a retry.
        AiService.AiTagResult tagResult = aiService.generateTags(request.getOriginalUrl());

        // The creation service saves and returns ONE mapping, including any slug suffix.
        // Do not build another UrlMapping here: that was the duplicate-slug bug.
        User currentUser = getCurrentUser();
        UrlMapping mapping = urlMappingCreationService.createMapping(
                request.getOriginalUrl(), request.getSlug(), currentUser,
                String.join(",", tagResult.tags()), tagResult.category());

        return buildShortenResponse(mapping);
    }

    // ── Redirect ─────────────────────────────────────────────────────────
    @Transactional
    public String resolveShortCode(String shortCode) {
        var target = urlLookupService.lookup(shortCode);
        LocalDateTime now = LocalDateTime.now();
        // This method runs even on cache hits, so expiry and counting are never skipped.
        if (target.expiresAt() != null && !target.expiresAt().isAfter(now)) {
            urlLookupService.evict(shortCode);
            throw new ApiException(HttpStatus.GONE, "This short URL has expired");
        }
        if (urlMappingRepository.incrementClickCount(target.id(), shortCode, now) == 0) {
            urlLookupService.evict(shortCode);
            var current = urlMappingRepository.findByShortCode(shortCode)
                    .orElseThrow(() -> new UrlNotFoundException(shortCode));
            if (current.getExpiresAt() != null && !current.getExpiresAt().isAfter(now)) {
                throw new ApiException(HttpStatus.GONE, "This short URL has expired");
            }
            // A replaced mapping must be resolved again, rather than redirecting to stale data.
            throw new UrlNotFoundException(shortCode);
        }
        return target.originalUrl();
    }

    // ── Debug ─────────────────────────────────────────────────────────────

    public List<UrlMapping> getAllMappings() {
        return urlMappingRepository.findAll();
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private ShortenResponse buildShortenResponse(UrlMapping mapping) {
        List<String> tags = mapping.getTags() != null && !mapping.getTags().isEmpty()
                ? Arrays.asList(mapping.getTags().split(","))
                : List.of();

        return ShortenResponse.builder()
                .shortUrl(buildShortUrl(mapping.getShortCode()))
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

    private String buildShortUrl(String shortCode) {
        return baseUrl.replaceAll("/+$", "") + "/api/urls/" + shortCode;
    }

}
