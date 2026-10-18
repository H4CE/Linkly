package io.linkly.url_shortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.linkly.url_shortener.entity.UrlMapping;
import io.linkly.url_shortener.entity.User;
import io.linkly.url_shortener.repository.UrlMappingRepository;
import io.linkly.url_shortener.repository.UserRepository;
import io.linkly.url_shortener.service.AiService;
import io.linkly.url_shortener.service.UrlLookupService;
import io.linkly.url_shortener.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.net.http.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses a disposable PostgreSQL database and real Redis; exercises actual HTTP dispatch. */
@EnabledIfSystemProperty(named = "linkly.test.postgres.port", matches = "\\d+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresRedirectIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"
                + System.getProperty("linkly.test.postgres.port") + "/linkly_audit");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "isolated-test-only");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.data.redis.port", () -> System.getProperty("linkly.test.redis.port"));
    }
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired UrlMappingRepository mappings;
    @Autowired CacheManager caches;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper mapper;
    @MockitoBean AiService ai;
    private User user;
    private UrlMapping mapping;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void setup() {
        user = users.save(User.builder().email("postgres-audit@example.com").password("unused").build());
        mapping = mappings.saveAndFlush(UrlMapping.builder().shortCode("postgres-audit")
                .originalUrl("https://example.com/audit").user(user).build());
    }
    @AfterEach void cleanup() {
        caches.getCache(UrlLookupService.CACHE_NAME).evict("postgres-audit");
        mappings.deleteAll(); users.deleteAll();
    }

    @Test void realHttpRedirectsCountEveryConcurrentHitAndListingShowsTotal() throws Exception {
        assertEquals(302, get("/api/urls/postgres-audit", null).statusCode());
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 40; i++) results.add(pool.submit(() -> get("/api/urls/postgres-audit", null).statusCode()));
            for (var result : results) assertEquals(302, result.get(20, TimeUnit.SECONDS));
        }
        assertEquals(41L, mappings.findById(mapping.getId()).orElseThrow().getClickCount());
        var listing = get("/api/urls", jwt.generateToken(user.getEmail()));
        assertEquals(200, listing.statusCode());
        assertEquals(41, mapper.readTree(listing.body()).get(0).path("clickCount").asInt());
    }

    @Test void cachedLinkCannotRedirectAfterDatabaseExpiry() throws Exception {
        assertEquals(302, get("/api/urls/postgres-audit", null).statusCode());
        mapping = mappings.findById(mapping.getId()).orElseThrow();
        mapping.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        mappings.saveAndFlush(mapping);
        assertEquals(410, get("/api/urls/postgres-audit", null).statusCode());
        assertEquals(1L, mappings.findById(mapping.getId()).orElseThrow().getClickCount());
    }

    @Test void errorDispatchPreserves404AndDeletedAccountReturns401() throws Exception {
        String token = jwt.generateToken(user.getEmail());
        assertEquals(404, get("/unmapped-route", token).statusCode());
        mappings.deleteAll(); users.deleteAll();
        var response = get("/api/urls", token);
        assertEquals(401, response.statusCode());
        assertEquals(401, mapper.readTree(response.body()).path("status").asInt());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(10)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
