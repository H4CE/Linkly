package io.linkly.url_shortener.service;

import io.linkly.url_shortener.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RateLimitService {
    private final StringRedisTemplate redisTemplate;

    // Increment and expiry are one atomic operation, preventing permanent limits if
    // the process stops between INCR and EXPIRE. Also repair old counters without a TTL.
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            if count <= tonumber(ARGV[2]) then return 1 else return 0 end
            """, Long.class);

    public boolean isSuggestAllowed(Long userId) { return isAllowed("suggest", userId, 10); }
    public boolean isRegenerateAllowed(Long userId) { return isAllowed("regenerate", userId, 5); }
    public boolean isConfirmAllowed(Long userId) { return isAllowed("confirm", userId, 10); }

    private boolean isAllowed(String operation, Long userId, int maximum) {
        try {
            Long result = redisTemplate.execute(LIMIT, List.of("ratelimit:" + operation + ":" + userId),
                    "3600", Integer.toString(maximum));
            if (result == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Rate limiter unavailable");
            return result == 1;
        } catch (DataAccessException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Rate limiter unavailable");
        }
    }
}
