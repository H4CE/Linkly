package io.linkly.url_shortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.linkly.url_shortener.config.TestCacheConfig;
import io.linkly.url_shortener.entity.UrlMapping;
import io.linkly.url_shortener.entity.User;
import io.linkly.url_shortener.repository.UrlMappingRepository;
import io.linkly.url_shortener.repository.UserRepository;
import io.linkly.url_shortener.service.AiService;
import io.linkly.url_shortener.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestCacheConfig.class)
@Transactional
class UrlListingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private AiService aiService;

    @MockitoBean
    private RateLimitService rateLimitService;

    private String token;
    private User currentUser;
    private User otherUser;

    @BeforeEach
    void setUp() throws Exception {
        cacheManager.getCache("urls").clear();
        token = register("owner@example.com");
        register("other@example.com");
        currentUser = userRepository.findByEmail("owner@example.com").orElseThrow();
        otherUser = userRepository.findByEmail("other@example.com").orElseThrow();
    }

    @Test
    @DisplayName("list returns only the authenticated user's URLs, newest first")
    void listMyUrls_returnsOnlyOwnedMappingsNewestFirst() throws Exception {
        saveMapping(currentUser, "first-link", "https://example.com/first?full=value&another=123");
        saveMapping(otherUser, "private-link", "https://other.example.com/private");
        saveMapping(currentUser, "second-link", "https://example.com/second");

        mockMvc.perform(get("/api/urls")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].originalUrl").value("https://example.com/second"))
                .andExpect(jsonPath("$[0].shortUrl").value("http://localhost:8080/api/urls/second-link"))
                .andExpect(jsonPath("$[1].originalUrl").value("https://example.com/first?full=value&another=123"))
                .andExpect(jsonPath("$[1].shortUrl").value("http://localhost:8080/api/urls/first-link"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].clickCount").value(0))
                .andExpect(jsonPath("$[0].tags[0]").value("java"))
                .andExpect(jsonPath("$[0].tags[1]").value("spring"))
                .andExpect(jsonPath("$[0].category").value("Technology"))
                .andExpect(jsonPath("$[0].user").doesNotExist())
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }

    @Test
    @DisplayName("list returns an empty array when only another user has URLs")
    void listMyUrls_withoutOwnedMappings_returnsEmptyArray() throws Exception {
        saveMapping(otherUser, "other-link", "https://example.com/other");

        mockMvc.perform(get("/api/urls")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("list without a JWT returns 401")
    void listMyUrls_withoutToken_returns401() throws Exception {
        saveMapping(currentUser, "owned-link", "https://example.com/owned");

        mockMvc.perform(get("/api/urls"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("list with an invalid JWT returns 401")
    void listMyUrls_withInvalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/urls")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a userId query parameter cannot expose another user's URLs")
    void listMyUrls_cannotOverrideAuthenticatedOwner() throws Exception {
        saveMapping(currentUser, "owned-link", "https://example.com/owned");
        saveMapping(otherUser, "other-link", "https://example.com/other");

        mockMvc.perform(get("/api/urls")
                        .param("userId", otherUser.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].originalUrl").value("https://example.com/owned"));
    }

    @Test
    @DisplayName("listing preserves clicks and returns a short URL that publicly redirects")
    void listMyUrls_isReadOnlyAndReturnsWorkingRedirect() throws Exception {
        UrlMapping mapping = saveMapping(currentUser, "working-link", "https://example.com/articles?topic=java");
        mapping.setClickCount(7L);
        urlMappingRepository.saveAndFlush(mapping);

        String response = mockMvc.perform(get("/api/urls")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clickCount").value(7))
                .andReturn().getResponse().getContentAsString();

        assertEquals(7L, urlMappingRepository.findById(mapping.getId()).orElseThrow().getClickCount());
        verifyNoInteractions(aiService, rateLimitService);

        String shortUrl = objectMapper.readTree(response).get(0).path("shortUrl").asText();
        mockMvc.perform(get(URI.create(shortUrl).getPath()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", mapping.getOriginalUrl()));
    }

    private String register(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "password123"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("token").asText();
    }

    private UrlMapping saveMapping(User owner, String shortCode, String originalUrl) {
        return urlMappingRepository.saveAndFlush(UrlMapping.builder()
                .user(owner)
                .shortCode(shortCode)
                .originalUrl(originalUrl)
                .tags("java,spring")
                .category("Technology")
                .build());
    }
}
