package io.linkly.url_shortener.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.linkly.url_shortener.config.RedisConfig;
import io.linkly.url_shortener.dto.RedirectTarget;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: point only at a disposable Redis instance, never a user's live Redis. */
@EnabledIfSystemProperty(named = "linkly.test.redis.port", matches = "\\d+")
class RedisIntegrationTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RateLimitService limits;
    private Long userId;

    @BeforeEach void setup() {
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("linkly.test.redis.port")));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        limits = new RateLimitService(redis);
        userId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }
    @AfterEach void cleanup() {
        for (String operation : new String[]{"suggest", "regenerate", "confirm", "shorten"})
            redis.delete("ratelimit:" + operation + ":" + userId);
        factory.destroy();
    }

    @Test void allAiOperationsHaveIndependentExpiringQuotas() {
        for (int i = 0; i < 10; i++) assertTrue(limits.isSuggestAllowed(userId));
        assertFalse(limits.isSuggestAllowed(userId));
        for (int i = 0; i < 5; i++) assertTrue(limits.isRegenerateAllowed(userId));
        assertFalse(limits.isRegenerateAllowed(userId));
        for (int i = 0; i < 10; i++) assertTrue(limits.isConfirmAllowed(userId));
        assertFalse(limits.isConfirmAllowed(userId));
        for (int i = 0; i < 30; i++) assertTrue(limits.isShortenAllowed(userId));
        assertFalse(limits.isShortenAllowed(userId));
        Long ttl = redis.getExpire("ratelimit:confirm:" + userId);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 3600);
    }

    @Test void legacyCounterWithoutExpiryGetsRepaired() {
        String key = "ratelimit:suggest:" + userId;
        redis.opsForValue().set(key, "10");
        assertFalse(limits.isSuggestAllowed(userId));
        assertTrue(redis.getExpire(key) > 0);
    }

    @Test void simultaneousRequestsCannotExceedQuota() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 40; i++) results.add(pool.submit(() -> {
                start.await(); return limits.isConfirmAllowed(userId);
            }));
            start.countDown();
            int allowed = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) allowed++;
            assertEquals(10, allowed);
        }
    }

    @Test void cachedRecordWithExpiryRoundTripsAcrossCacheManagers() {
        var config = new RedisConfig();
        var mapper = new ObjectMapper().findAndRegisterModules();
        var writer = (org.springframework.data.redis.cache.RedisCacheManager) config.cacheManager(factory, mapper);
        var reader = (org.springframework.data.redis.cache.RedisCacheManager) config.cacheManager(factory, mapper);
        writer.afterPropertiesSet(); reader.afterPropertiesSet();
        String key = "test-" + userId;
        var target = new RedirectTarget(123L, "https://example.com", LocalDateTime.now().plusMinutes(5));
        try {
            writer.getCache(UrlLookupService.CACHE_NAME).put(key, target);
            assertEquals(target, reader.getCache(UrlLookupService.CACHE_NAME).get(key, RedirectTarget.class));
            assertTrue(redis.getExpire(UrlLookupService.CACHE_NAME + "::" + key) > 0);
        } finally { writer.getCache(UrlLookupService.CACHE_NAME).evict(key); }
    }
}
