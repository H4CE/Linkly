package io.linkly.url_shortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.linkly.url_shortener.config.TestCacheConfig;
import io.linkly.url_shortener.service.AiService;
import io.linkly.url_shortener.service.RateLimitService;
import io.linkly.url_shortener.util.SlugVerificationUtil;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestCacheConfig.class)
@Transactional
class UrlMappingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SlugVerificationUtil slugVerificationUtil;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private AiService aiService;

    @MockitoBean
    private RateLimitService rateLimitService;

    private String token;
    private static final String TEST_URL = "https://www.baeldung.com/spring-boot";
    private static final String TEST_SLUG = "spring-boot-guide";

    @BeforeEach
    void setUp() throws Exception {
        cacheManager.getCache("urls").clear();
        when(rateLimitService.isSuggestAllowed(anyLong())).thenReturn(true);
        when(rateLimitService.isRegenerateAllowed(anyLong())).thenReturn(true);

        // Register a user and get token before each test
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "testuser@gmail.com",
                            "password": "password123"
                        }
                        """))
                .andExpect(status().isCreated())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        token = objectMapper.readTree(response)
                .path("token")
                .asText();

        // Set up AI mock responses
        when(aiService.generateSlug(anyString()))
                .thenReturn(TEST_SLUG);
        when(aiService.regenerateSlug(anyString(), anyString()))
                .thenReturn("baeldung-spring-tutorial");
        when(aiService.checkIfSafe(anyString()))
                .thenReturn(true);
        when(aiService.generateTags(anyString()))
                .thenReturn(new AiService.AiTagResult(
                        List.of("java", "spring", "backend"),
                        "Technology"));
    }

    // ── Suggest tests ─────────────────────────────────────────────────

    @Test
    @DisplayName("suggest with valid URL returns suggested slug and short URL")
    void suggest_validUrl_returnsSuggestedSlug() throws Exception {
        mockMvc.perform(post("/api/urls/suggest")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "originalUrl": "https://www.baeldung.com/spring-boot"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedSlug").value(TEST_SLUG))
                .andExpect(jsonPath("$.suggestedShortUrl").exists())
                .andExpect(jsonPath("$.verificationToken").exists())
                .andExpect(jsonPath("$.originalUrl")
                        .value("https://www.baeldung.com/spring-boot"));
    }

    @Test
    @DisplayName("suggest without token returns 401")
    void suggest_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/urls/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "originalUrl": "https://www.baeldung.com/spring-boot"
                        }
                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("suggest with invalid URL returns 400")
    void suggest_invalidUrl_returns400() throws Exception {
        mockMvc.perform(post("/api/urls/suggest")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "originalUrl": "not-a-valid-url"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.originalUrl").exists());
    }

    // ── Confirm tests ─────────────────────────────────────────────────

    @Test
    @DisplayName("confirm with valid slug and token saves URL and returns response")
    void confirm_validSlugAndToken_returns201() throws Exception {
        // Generate a real verification token
        String verificationToken = slugVerificationUtil
                .generateToken(TEST_URL, TEST_SLUG);

        mockMvc.perform(post("/api/urls/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                        {
                            "originalUrl": "%s",
                            "slug": "%s",
                            "verificationToken": "%s"
                        }
                        """, TEST_URL, TEST_SLUG, verificationToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortUrl").exists())
                .andExpect(jsonPath("$.originalUrl").value(TEST_URL))
                .andExpect(jsonPath("$.tags").isArray())
                .andExpect(jsonPath("$.category").value("Technology"));
    }

    @Test
    @DisplayName("confirm with tampered slug returns 400")
    void confirm_tamperedSlug_returns400() throws Exception {
        String verificationToken = slugVerificationUtil
                .generateToken(TEST_URL, TEST_SLUG);

        mockMvc.perform(post("/api/urls/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                        {
                            "originalUrl": "%s",
                            "slug": "tampered-slug",
                            "verificationToken": "%s"
                        }
                        """, TEST_URL, verificationToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("confirm with unsafe URL returns 400")
    void confirm_unsafeUrl_returns400() throws Exception {
        when(aiService.checkIfSafe(anyString())).thenReturn(false);

        String verificationToken = slugVerificationUtil
                .generateToken(TEST_URL, TEST_SLUG);

        mockMvc.perform(post("/api/urls/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                        {
                            "originalUrl": "%s",
                            "slug": "%s",
                            "verificationToken": "%s"
                        }
                        """, TEST_URL, TEST_SLUG, verificationToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("confirm without token returns 401")
    void confirm_noToken_returns401() throws Exception {
        String verificationToken = slugVerificationUtil
                .generateToken(TEST_URL, TEST_SLUG);

        mockMvc.perform(post("/api/urls/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                        {
                            "originalUrl": "%s",
                            "slug": "%s",
                            "verificationToken": "%s"
                        }
                        """, TEST_URL, TEST_SLUG, verificationToken)))
                .andExpect(status().isUnauthorized());
    }

    // ── Redirect tests ────────────────────────────────────────────────

    @Test
    @DisplayName("redirect with valid short code returns 302 with Location header")
    void redirect_validShortCode_returns302() throws Exception {
        // First confirm a URL to save it
        String verificationToken = slugVerificationUtil
                .generateToken(TEST_URL, TEST_SLUG);

        mockMvc.perform(post("/api/urls/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                        {
                            "originalUrl": "%s",
                            "slug": "%s",
                            "verificationToken": "%s"
                        }
                        """, TEST_URL, TEST_SLUG, verificationToken)))
                .andExpect(status().isCreated());

        // Then redirect
        mockMvc.perform(get("/api/urls/" + TEST_SLUG))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", TEST_URL));
    }

    @Test
    @DisplayName("redirect with invalid short code returns 404")
    void redirect_invalidShortCode_returns404() throws Exception {
        mockMvc.perform(get("/api/urls/nonexistent-code"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("redirect is public — no token needed")
    void redirect_noToken_isAccessible() throws Exception {
        // Save a URL first
        String verificationToken = slugVerificationUtil
                .generateToken(TEST_URL, TEST_SLUG);

        mockMvc.perform(post("/api/urls/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                        {
                            "originalUrl": "%s",
                            "slug": "%s",
                            "verificationToken": "%s"
                        }
                        """, TEST_URL, TEST_SLUG, verificationToken)))
                .andExpect(status().isCreated());

        // Access redirect without token
        mockMvc.perform(get("/api/urls/" + TEST_SLUG))
                .andExpect(status().isFound());
    }

}
