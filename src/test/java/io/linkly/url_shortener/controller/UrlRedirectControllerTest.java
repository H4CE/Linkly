package io.linkly.url_shortener.controller;

import io.linkly.url_shortener.config.TestCacheConfig;
import io.linkly.url_shortener.dto.RedirectTarget;
import io.linkly.url_shortener.entity.UrlMapping;
import io.linkly.url_shortener.entity.User;
import io.linkly.url_shortener.repository.UrlMappingRepository;
import io.linkly.url_shortener.repository.UserRepository;
import io.linkly.url_shortener.service.AiService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:redirectTests;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestCacheConfig.class)
class UrlRedirectControllerTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @MockitoSpyBean UrlMappingRepository mappings;
    @MockitoSpyBean(name = "testCacheManager") CacheManager caches;
    @MockitoBean AiService ai;
    private UrlMapping mapping;

    @BeforeEach void setup() {
        caches.getCache("urlDestinations").clear();
        var user = users.save(User.builder().email("redirect@example.com").password("unused").build());
        mapping = mappings.saveAndFlush(UrlMapping.builder().shortCode("cached-link")
                .originalUrl("https://example.com/a?b=c").user(user).build());
        clearInvocations(mappings);
    }

    @AfterEach void cleanup() {
        reset(caches);
        caches.getCache("urlDestinations").clear();
        mappings.deleteAll();
        users.deleteAll();
    }

    @Test void repeatedCacheHitsEachCountButOnlyLoadDestinationOnce() throws Exception {
        for (int i = 0; i < 5; i++) redirect();
        verify(mappings, times(1)).findByShortCode("cached-link");
        assertEquals(5L, mappings.findById(mapping.getId()).orElseThrow().getClickCount());
    }

    @Test void simultaneousCacheHitsDoNotLoseClicks() throws Exception {
        redirect(); // Warm the cache before releasing concurrent requests.
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<?>>();
            for (int i = 0; i < 40; i++) results.add(pool.submit(() -> {
                start.await();
                redirect();
                return null;
            }));
            start.countDown();
            for (var result : results) result.get(20, TimeUnit.SECONDS);
        }
        assertEquals(41L, mappings.findById(mapping.getId()).orElseThrow().getClickCount());
    }

    @Test void expiryChangedInDatabaseIsRespectedOnCacheHit() throws Exception {
        redirect();
        mapping = mappings.findById(mapping.getId()).orElseThrow();
        mapping.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        mappings.saveAndFlush(mapping);
        mvc.perform(get("/api/urls/cached-link")).andExpect(status().isGone());
        assertEquals(1L, mappings.findById(mapping.getId()).orElseThrow().getClickCount());
        assertNull(caches.getCache("urlDestinations").get("cached-link"));
    }

    @Test void cachedExpiryIsCheckedBeforeCounting() throws Exception {
        redirect();
        caches.getCache("urlDestinations").put("cached-link", new RedirectTarget(mapping.getId(),
                mapping.getOriginalUrl(), LocalDateTime.now().minusSeconds(1)));
        mvc.perform(get("/api/urls/cached-link")).andExpect(status().isGone());
        assertEquals(1L, mappings.findById(mapping.getId()).orElseThrow().getClickCount());
    }

    @Test void cachedDeletedMappingDoesNotRedirect() throws Exception {
        redirect();
        mappings.deleteById(mapping.getId());
        mvc.perform(get("/api/urls/cached-link")).andExpect(status().isNotFound());
    }

    @Test void cacheReadAndWriteFailuresFallBackToDatabaseAndStillCount() throws Exception {
        Cache broken = mock(Cache.class);
        when(broken.getName()).thenReturn("urlDestinations");
        when(broken.get(any())).thenThrow(new DataAccessResourceFailureException("offline"));
        doThrow(new DataAccessResourceFailureException("offline")).when(broken).put(any(), any());
        doReturn(broken).when(caches).getCache("urlDestinations");
        for (int i = 0; i < 3; i++) redirect();
        assertEquals(3L, mappings.findById(mapping.getId()).orElseThrow().getClickCount());
        verify(mappings, times(3)).findByShortCode("cached-link");
    }

    @Test void missingMappingReturns404AndIsNotCached() throws Exception {
        mvc.perform(get("/api/urls/missing")).andExpect(status().isNotFound());
        assertNull(caches.getCache("urlDestinations").get("missing"));
    }

    private void redirect() throws Exception {
        mvc.perform(get("/api/urls/cached-link")).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/a?b=c"));
    }
}
