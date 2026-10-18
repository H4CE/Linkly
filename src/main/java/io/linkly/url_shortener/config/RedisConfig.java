package io.linkly.url_shortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.linkly.url_shortener.dto.RedirectTarget;
import io.linkly.url_shortener.service.UrlLookupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Slf4j
@Configuration
public class RedisConfig implements CachingConfigurer {
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper mapper) {
        // Use Boot's Java-time-aware mapper and an explicit type for the cached record.
        var serializer = new Jackson2JsonRedisSerializer<>(mapper, RedirectTarget.class);
        var config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1)).disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
        return RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration(UrlLookupService.CACHE_NAME, config).build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        // Redis is an optimization for redirects: a cache outage falls back to the DB.
        // Rate-limit failures are handled separately and still block paid AI calls.
        return new CacheErrorHandler() {
            private void report(Cache cache) {
                log.warn("Cache operation failed for {}; using database fallback", cache.getName());
            }
            public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) { report(cache); }
            public void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value) { report(cache); }
            public void handleCacheEvictError(RuntimeException ex, Cache cache, Object key) { report(cache); }
            public void handleCacheClearError(RuntimeException ex, Cache cache) { report(cache); }
        };
    }
}
