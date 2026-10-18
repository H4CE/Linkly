package io.linkly.url_shortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.linkly.url_shortener.config.TestCacheConfig;
import io.linkly.url_shortener.entity.UrlMapping;
import io.linkly.url_shortener.entity.User;
import io.linkly.url_shortener.repository.UrlMappingRepository;
import io.linkly.url_shortener.repository.UserRepository;
import io.linkly.url_shortener.service.AiService;
import io.linkly.url_shortener.safety.ContentSafetyService;
import io.linkly.url_shortener.util.RandomShortCodeGenerator;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import io.linkly.url_shortener.service.RateLimitService;
import io.linkly.url_shortener.util.Base62Encoder;
import io.linkly.url_shortener.util.SlugVerificationUtil;
import org.junit.jupiter.api.AfterEach;
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

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:urlMappingControllerTest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@Import(TestCacheConfig.class)
class UrlMappingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SlugVerificationUtil slugVerificationUtil;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private Base62Encoder base62Encoder;

    @MockitoBean
    private AiService aiService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private ContentSafetyService contentSafetyService;

    @MockitoSpyBean
    private RandomShortCodeGenerator randomShortCodeGenerator;

    private String token;
    private static final String TEST_URL = "https://www.baeldung.com/spring-boot";
    private static final String TEST_SLUG = "spring-boot-guide";

    @BeforeEach
    void setUp() throws Exception {
        cacheManager.getCache("urlDestinations").clear();
        when(contentSafetyService.inspect(anyString())).thenReturn("Fetched article about Spring Boot.");
        when(rateLimitService.isShortenAllowed(anyLong())).thenReturn(true);
        when(rateLimitService.isSuggestAllowed(anyLong())).thenReturn(true);
        when(rateLimitService.isConfirmAllowed(anyLong())).thenReturn(true);
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
        when(aiService.checkIfSafe(anyString(), anyString()))
                .thenReturn(true);
        when(aiService.generateTags(anyString()))
                .thenReturn(new AiService.AiTagResult(
                        List.of("java", "spring", "backend"),
                        "Technology"));
    }

    @AfterEach
    void tearDown() {
        // Creation owns real transactions so collision retries can be tested after
        // rollback. Clean the isolated H2 database explicitly instead of wrapping
        // each test in a transaction that would hide users from REQUIRES_NEW.
        cacheManager.getCache("urlDestinations").clear();
        urlMappingRepository.deleteAll();
        userRepository.deleteAll();
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
    @DisplayName("a slug owned by another user creates one suffixed mapping and preserves both links")
    void confirm_takenSlug_createsOneMappingAndPreservesExistingLink() throws Exception {
        User currentUser = userRepository.findByEmail("testuser@gmail.com").orElseThrow();
        User otherUser = userRepository.saveAndFlush(User.builder()
                .email("other-owner@example.com")
                .password(currentUser.getPassword())
                .build());
        UrlMapping original = saveMapping(otherUser, TEST_SLUG, "https://example.com/original");
        original.setClickCount(9L);
        urlMappingRepository.saveAndFlush(original);

        String shortUrl = confirmUrl(TEST_URL, TEST_SLUG);

        List<UrlMapping> ownedMappings = urlMappingRepository
                .findAllByUserIdOrderByCreatedAtDescIdDesc(currentUser.getId());
        assertEquals(2L, urlMappingRepository.count());
        assertEquals(1, ownedMappings.size());
        UrlMapping created = ownedMappings.getFirst();
        assertEquals(TEST_SLUG + "-" + base62Encoder.encode(created.getId()), created.getShortCode());
        assertEquals(TEST_URL, created.getOriginalUrl());
        assertEquals("java,spring,backend", created.getTags());
        assertEquals("Technology", created.getCategory());
        assertEquals(0L, created.getClickCount());
        assertEquals("http://localhost:8080/api/urls/" + created.getShortCode(), shortUrl);

        UrlMapping preserved = urlMappingRepository.findById(original.getId()).orElseThrow();
        assertEquals(TEST_SLUG, preserved.getShortCode());
        assertEquals("https://example.com/original", preserved.getOriginalUrl());
        assertEquals(otherUser.getId(), preserved.getUser().getId());
        assertEquals(9L, preserved.getClickCount());

        mockMvc.perform(get(URI.create(shortUrl).getPath()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", TEST_URL));
        mockMvc.perform(get("/api/urls/" + TEST_SLUG))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/original"));
    }

    @Test
    @DisplayName("repeated confirmations create one distinct link per request without temporary rows")
    void confirm_repeatedSlug_createsExactlyOneMappingPerRequest() throws Exception {
        Set<String> shortUrls = new HashSet<>();
        for (int request = 0; request < 3; request++) {
            shortUrls.add(confirmUrl(TEST_URL, TEST_SLUG));
        }

        assertEquals(3, shortUrls.size());
        assertEquals(3L, urlMappingRepository.count());
        assertTrue(shortUrls.contains("http://localhost:8080/api/urls/" + TEST_SLUG));
        assertTrue(urlMappingRepository.findAll().stream()
                .allMatch(mapping -> mapping.getShortCode().startsWith(TEST_SLUG)));
    }

    @Test
    @DisplayName("a collision with a 100-character slug leaves room for the Base62 suffix")
    void confirm_maximumLengthSlug_keepsFinalCodeWithinColumnLimit() throws Exception {
        String requestedSlug = "a".repeat(100);
        User currentUser = userRepository.findByEmail("testuser@gmail.com").orElseThrow();
        saveMapping(currentUser, requestedSlug, "https://example.com/original");

        String shortUrl = confirmUrl(TEST_URL, requestedSlug);
        String finalCode = URI.create(shortUrl).getPath().substring("/api/urls/".length());
        UrlMapping created = urlMappingRepository.findByShortCode(finalCode).orElseThrow();
        String suffix = "-" + base62Encoder.encode(created.getId());

        assertEquals(100, finalCode.length());
        assertEquals("a".repeat(100 - suffix.length()) + suffix, finalCode);
        assertEquals(2L, urlMappingRepository.count());
        assertEquals(TEST_URL, created.getOriginalUrl());
    }

    @Test
    @DisplayName("an occupied hybrid code rolls back and retries with a fresh ID")
    void confirm_occupiedHybridCode_retriesAfterRollback() throws Exception {
        User currentUser = userRepository.findByEmail("testuser@gmail.com").orElseThrow();
        saveMapping(currentUser, TEST_SLUG, "https://example.com/original");
        UrlMapping blocker = saveMapping(currentUser, "blocker", "https://example.com/blocker");

        // Reserve the suffix the next identity allocation would produce. This
        // forces a real database uniqueness failure rather than just a precheck.
        long blockedId = blocker.getId() + 1;
        String occupiedCode = TEST_SLUG + "-" + base62Encoder.encode(blockedId);
        blocker.setShortCode(occupiedCode);
        urlMappingRepository.saveAndFlush(blocker);

        String shortUrl = confirmUrl(TEST_URL, TEST_SLUG);
        String finalCode = URI.create(shortUrl).getPath().substring("/api/urls/".length());
        UrlMapping created = urlMappingRepository.findByShortCode(finalCode).orElseThrow();

        assertEquals(3L, urlMappingRepository.count());
        assertTrue(created.getId() > blockedId);
        assertNotEquals(occupiedCode, finalCode);
        assertEquals(TEST_SLUG + "-" + base62Encoder.encode(created.getId()), finalCode);
        assertEquals("https://example.com/blocker", urlMappingRepository
                .findByShortCode(occupiedCode).orElseThrow().getOriginalUrl());
        assertTrue(urlMappingRepository.findAll().stream()
                .noneMatch(mapping -> mapping.getShortCode().startsWith("pending_")));
        verify(aiService, times(1)).checkIfSafe(org.mockito.ArgumentMatchers.eq(TEST_URL), org.mockito.ArgumentMatchers.eq("Fetched article about Spring Boot."));
        verify(aiService, times(1)).generateTags(TEST_URL);
    }

    @Test
    @DisplayName("simultaneous confirmations of one slug each produce a distinct saved link")
    void confirm_concurrentSameSlug_createsDistinctMappings() throws Exception {
        int requestCount = 4;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> responses = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(requestCount)) {
            for (int request = 0; request < requestCount; request++) {
                responses.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return confirmUrl(TEST_URL, TEST_SLUG);
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            Set<String> shortUrls = new HashSet<>();
            for (Future<String> response : responses) {
                shortUrls.add(response.get(15, TimeUnit.SECONDS));
            }
            assertEquals(requestCount, shortUrls.size());
        }

        assertEquals(requestCount, urlMappingRepository.count());
        assertTrue(urlMappingRepository.findAll().stream()
                .allMatch(mapping -> mapping.getShortCode().startsWith(TEST_SLUG)));
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
        when(aiService.checkIfSafe(anyString(), anyString())).thenReturn(false);

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

    private String confirmUrl(String originalUrl, String slug) throws Exception {
        String response = mockMvc.perform(post("/api/urls/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "originalUrl", originalUrl,
                                "slug", slug,
                                "verificationToken", slugVerificationUtil.generateToken(originalUrl, slug)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("shortUrl").asText();
    }

    private UrlMapping saveMapping(User owner, String shortCode, String originalUrl) {
        return urlMappingRepository.saveAndFlush(UrlMapping.builder()
                .user(owner)
                .shortCode(shortCode)
                .originalUrl(originalUrl)
                .build());
    }
    @Test
    void previewUrlsUseTheActualRedirectRoute() throws Exception {
        mockMvc.perform(post("/api/urls/suggest").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", TEST_URL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedShortUrl").value("http://localhost:8080/api/urls/" + TEST_SLUG));
        mockMvc.perform(post("/api/urls/regenerate").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", TEST_URL,
                                "previousSlug", TEST_SLUG, "verificationToken", slugVerificationUtil.generateToken(TEST_URL, TEST_SLUG)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedShortUrl").value("http://localhost:8080/api/urls/baeldung-spring-tutorial"));
    }

    @Test void confirmationQuotaPreventsAiCallsAndWrites() throws Exception {
        when(rateLimitService.isConfirmAllowed(anyLong())).thenReturn(false);
        mockMvc.perform(post("/api/urls/confirm").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", TEST_URL, "slug", TEST_SLUG,
                                "verificationToken", slugVerificationUtil.generateToken(TEST_URL, TEST_SLUG)))))
                .andExpect(status().isTooManyRequests());
        verify(aiService, org.mockito.Mockito.never()).checkIfSafe(anyString(), anyString());
        assertEquals(0, urlMappingRepository.count());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"https://", "https:///missing-host", "https://a b.com", "https://user:password@example.com", "https://example.com:99999"})
    void malformedDestinationsAreRejectedBeforeAi(String url) throws Exception {
        mockMvc.perform(post("/api/urls/suggest").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", url))))
                .andExpect(status().isBadRequest());
        verify(aiService, org.mockito.Mockito.never()).generateSlug(anyString());
    }

    @Test void deletedUserTokenReturnsJson401() throws Exception {
        userRepository.deleteAll();
        mockMvc.perform(get("/api/urls").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }

    @Test void aiOutageReturns503WithoutSavingMapping() throws Exception {
        when(aiService.checkIfSafe(anyString(), anyString())).thenThrow(new io.linkly.url_shortener.exception.ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "AI service unavailable"));
        mockMvc.perform(post("/api/urls/confirm").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", TEST_URL, "slug", TEST_SLUG,
                                "verificationToken", slugVerificationUtil.generateToken(TEST_URL, TEST_SLUG)))))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0, urlMappingRepository.count());
    }
    @Test void basicShorteningWorksWhenGeminiIsUnavailable() throws Exception {
        // No stubbing can rescue an accidental AI call: verify none occur in this route.
        var result = mockMvc.perform(post("/api/urls/shorten").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", TEST_URL))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.originalUrl").value(TEST_URL))
                .andExpect(jsonPath("$.clickCount").value(0)).andExpect(jsonPath("$.tags").isEmpty())
                .andExpect(jsonPath("$.category").value("Uncategorized")).andReturn();
        String shortUrl = objectMapper.readTree(result.getResponse().getContentAsString()).path("shortUrl").asText();
        assertTrue(shortUrl.matches("http://localhost:8080/api/urls/[A-Za-z0-9]{10}"));
        assertEquals(1, urlMappingRepository.count());
        assertEquals("testuser@gmail.com", userRepository.findById(urlMappingRepository.findAll().getFirst()
                .getUser().getId()).orElseThrow().getEmail());
        mockMvc.perform(get(URI.create(shortUrl).getPath())).andExpect(status().isFound())
                .andExpect(header().string("Location", TEST_URL));
        mockMvc.perform(get("/api/urls").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].shortUrl").value(shortUrl))
                .andExpect(jsonPath("$[0].clickCount").value(1));
        verify(contentSafetyService).inspect(TEST_URL);
        org.mockito.Mockito.verifyNoInteractions(aiService);
    }

    @Test void basicShorteningRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/urls/shorten").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", TEST_URL))))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(contentSafetyService, aiService);
    }

    @Test void basicShorteningRejectsInvalidUrlsBeforeFetching() throws Exception {
        mockMvc.perform(post("/api/urls/shorten").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"originalUrl\":\"https:///missing-host\"}"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(contentSafetyService, aiService);
    }

    @Test void basicQuotaIsCheckedBeforeFetching() throws Exception {
        when(rateLimitService.isShortenAllowed(anyLong())).thenReturn(false);
        mockMvc.perform(post("/api/urls/shorten").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", TEST_URL))))
                .andExpect(status().isTooManyRequests());
        assertEquals(0, urlMappingRepository.count());
        org.mockito.Mockito.verifyNoInteractions(contentSafetyService, aiService);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 422, 503})
    void basicShorteningDoesNotSaveRejectedOrUnscannableContent(int statusCode) throws Exception {
        when(contentSafetyService.inspect(anyString())).thenThrow(new io.linkly.url_shortener.exception.ApiException(
                org.springframework.http.HttpStatus.valueOf(statusCode), "Content check did not pass"));
        mockMvc.perform(post("/api/urls/shorten").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", TEST_URL))))
                .andExpect(status().is(statusCode));
        assertEquals(0, urlMappingRepository.count());
        org.mockito.Mockito.verifyNoInteractions(aiService);
    }

    @Test void basicCodeCollisionPreservesExistingOwnerAndCreatesOneMapping() throws Exception {
        var owner = userRepository.findByEmail("testuser@gmail.com").orElseThrow();
        saveMapping(owner, "FixedCode1", "https://example.com/existing");
        org.mockito.Mockito.doReturn("FixedCode1").when(randomShortCodeGenerator).generate();
        mockMvc.perform(post("/api/urls/shorten").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", TEST_URL))))
                .andExpect(status().isCreated());
        assertEquals(2, urlMappingRepository.count());
        assertEquals("https://example.com/existing", urlMappingRepository.findByShortCode("FixedCode1")
                .orElseThrow().getOriginalUrl());
        assertEquals(1, urlMappingRepository.findAll().stream().filter(m -> m.getOriginalUrl().equals(TEST_URL)).count());
        org.mockito.Mockito.verifyNoInteractions(aiService);
    }

    @Test void localContentRejectionAlsoBlocksAiConfirmationBeforeGemini() throws Exception {
        when(contentSafetyService.inspect(anyString())).thenThrow(new io.linkly.url_shortener.exception.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Page collects passwords insecurely"));
        mockMvc.perform(post("/api/urls/confirm").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", TEST_URL, "slug", TEST_SLUG,
                                "verificationToken", slugVerificationUtil.generateToken(TEST_URL, TEST_SLUG)))))
                .andExpect(status().isBadRequest());
        assertEquals(0, urlMappingRepository.count());
        org.mockito.Mockito.verifyNoInteractions(aiService);
    }
}
