package io.linkly.url_shortener.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final int MAX_SUGGEST_REQUESTS = 10;
    private static final int MAX_REGENERATE_REQUESTS = 5;
    private static final Duration WINDOW = Duration.ofHours(1);

    public boolean isSuggestAllowed(Long userId) {
        return isAllowed("ratelimit:suggest:" + userId, MAX_SUGGEST_REQUESTS);
    }

    public boolean isRegenerateAllowed(Long userId) {
        return isAllowed("ratelimit:regenerate:" + userId, MAX_REGENERATE_REQUESTS);
    }

    private boolean isAllowed(String key, int maxRequests) {
        Long count = redisTemplate.opsForValue().increment(key);

        if (count == null) return true;

        // First request — set expiry window
        if (count == 1) {
            redisTemplate.expire(key, WINDOW);
        }

        return count <= maxRequests;
    }

}