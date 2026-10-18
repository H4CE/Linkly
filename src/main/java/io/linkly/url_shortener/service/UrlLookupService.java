package io.linkly.url_shortener.service;

import io.linkly.url_shortener.dto.RedirectTarget;
import io.linkly.url_shortener.exception.UrlNotFoundException;
import io.linkly.url_shortener.repository.UrlMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UrlLookupService {
    // A new namespace avoids reading old cache entries containing only a URL string.
    public static final String CACHE_NAME = "urlDestinations";
    private final UrlMappingRepository repository;

    // A separate bean ensures the cache proxy is used; resolving/counting itself is never cached.
    @Cacheable(value = CACHE_NAME, key = "#shortCode")
    @Transactional(readOnly = true)
    public RedirectTarget lookup(String shortCode) {
        var mapping = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        return new RedirectTarget(mapping.getId(), mapping.getOriginalUrl(), mapping.getExpiresAt());
    }

    @CacheEvict(value = CACHE_NAME, key = "#shortCode")
    public void evict(String shortCode) {
        // Invoked when a cached mapping is expired, deleted, or changed in the database.
    }
}
