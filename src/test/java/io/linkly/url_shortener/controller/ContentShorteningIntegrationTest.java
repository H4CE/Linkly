package io.linkly.url_shortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.linkly.url_shortener.config.TestCacheConfig;
import io.linkly.url_shortener.repository.UrlMappingRepository;
import io.linkly.url_shortener.repository.UserRepository;
import io.linkly.url_shortener.safety.PublicUrlPolicy;
import io.linkly.url_shortener.safety.PageContentFetcher;
import io.linkly.url_shortener.service.AiService;
import io.linkly.url_shortener.service.RateLimitService;
import io.linkly.url_shortener.util.SlugVerificationUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:contentFlow;DB_CLOSE_DELAY=-1", "app.gemini.api-key="})
@AutoConfigureMockMvc
@Import(TestCacheConfig.class)
class ContentShorteningIntegrationTest {
    private static WireMockServer pages;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UrlMappingRepository mappings;
    @Autowired UserRepository users;
    @Autowired CacheManager caches;
    @Autowired SlugVerificationUtil tokens;
    @MockitoSpyBean AiService ai;
    @MockitoSpyBean PageContentFetcher pageFetcher;
    @MockitoBean RateLimitService limits;
    @MockitoBean PublicUrlPolicy policy;
    private String jwt;
    private String url;

    @BeforeAll static void start() { pages = new WireMockServer(wireMockConfig().dynamicPort()); pages.start(); }
    @AfterAll static void stop() { pages.stop(); }
    @BeforeEach void setup() throws Exception {
        pages.resetAll();
        caches.getCache("urlDestinations").clear();
        when(limits.isShortenAllowed(anyLong())).thenReturn(true);
        when(limits.isConfirmAllowed(anyLong())).thenReturn(true);
        // Only tests substitute the policy; AI-flow tests use the real fetcher/parser/persistence.
        when(policy.resolve(any())).thenAnswer(call -> new PublicUrlPolicy.Target(call.getArgument(0),
                new InetAddress[]{InetAddress.getByName("127.0.0.1")}));
        url = "http://interview-page.test:" + pages.port() + "/article";
        var auth = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"content-flow@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated()).andReturn();
        jwt = mapper.readTree(auth.getResponse().getContentAsString()).path("token").asText();
    }
    @AfterEach void cleanup() {
        caches.getCache("urlDestinations").clear(); mappings.deleteAll(); users.deleteAll();
    }

    @Test void basicCreatesAUsableLinkWithoutFetchingOrAGeminiKey() throws Exception {
        article("<h1>A real fetched article</h1><p>Useful tutorial content.</p>");
        var saved = mvc.perform(post("/api/urls/shorten").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("originalUrl", url))))
                .andExpect(status().isCreated()).andReturn();
        String link = mapper.readTree(saved.getResponse().getContentAsString()).path("shortUrl").asText();
        mvc.perform(get(URI.create(link).getPath())).andExpect(status().isFound()).andExpect(header().string("Location", url));
        assertTrue(pages.getAllServeEvents().isEmpty());
        verifyNoInteractions(pageFetcher, policy, ai);
    }

    @Test void basicNeverClassifiesContentOrContactsTheDestination() throws Exception {
        article("<h1>A useful article</h1><p>Learn Spring Boot.</p>");
        mvc.perform(post("/api/urls/shorten").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("originalUrl", url))))
                .andExpect(status().isCreated());
        pages.resetAll();
        article("<h1>Claim a wallet reward</h1><form><input name='seed_phrase'></form>");
        mvc.perform(post("/api/urls/shorten").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("originalUrl", url))))
                .andExpect(status().isCreated());
        assertEquals(2, mappings.count());
        assertTrue(pages.getAllServeEvents().isEmpty());
        verifyNoInteractions(pageFetcher, policy, ai);
    }

    @Test void aiConfirmationPassesFetchedTextToGeminiAndBlocksIfRequiredAiIsUnavailable() throws Exception {
        article("<h1>Fetched article</h1><p>This body was downloaded, not inferred from the URL.</p>");
        mvc.perform(post("/api/urls/confirm").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("originalUrl", url,
                                "slug", "article", "verificationToken", tokens.generateToken(url, "article")))))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0, mappings.count());
        verify(ai).checkIfSafe(eq(url), contains("This body was downloaded, not inferred from the URL."));
    }

    @Test void invalidSuggestionTokenDoesNotTriggerAnyPageFetch() throws Exception {
        mvc.perform(post("/api/urls/confirm").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("originalUrl", url,
                                "slug", "article", "verificationToken", "invalid"))))
                .andExpect(status().isBadRequest());
        assertTrue(pages.getAllServeEvents().isEmpty());
        verifyNoInteractions(ai);
    }

    @Test void geminiAloneRejectsDownloadedSuspiciousContent() throws Exception {
        article("<h1>Claim your reward</h1><form><input name='seed_phrase'></form>");
        doReturn(false).when(ai).checkIfSafe(anyString(), anyString());
        mvc.perform(post("/api/urls/confirm").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("originalUrl", url,
                                "slug", "article", "verificationToken", tokens.generateToken(url, "article")))))
                .andExpect(status().isBadRequest());
        assertEquals(0, mappings.count());
        pages.verify(1, getRequestedFor(urlEqualTo("/article")));
        verify(ai).checkIfSafe(eq(url), contains("seed_phrase"));
    }

    @Test void javaDoesNotOverrideGeminisApprovalWithManualRules() throws Exception {
        article("<h1>A page for assessment</h1><form action='https://other.test' method='get'>"
                + "<input type='password' name='seed_phrase'></form><script>eval(atob('YWJj'))</script>");
        doReturn(true).when(ai).checkIfSafe(anyString(), anyString());
        doReturn(new AiService.AiTagResult(java.util.List.of(), "Uncategorized")).when(ai).generateTags(anyString());
        mvc.perform(post("/api/urls/confirm").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("originalUrl", url,
                                "slug", "article", "verificationToken", tokens.generateToken(url, "article")))))
                .andExpect(status().isCreated());
        assertEquals(1, mappings.count());
        verify(ai).checkIfSafe(eq(url), contains("seed_phrase"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"https://unreachable.invalid/page",
            "https://example.com/document.pdf", "http://127.0.0.1:8080/private"})
    void basicSavesValidUrlsWithoutTryingToReadThem(String originalUrl) throws Exception {
        mvc.perform(post("/api/urls/shorten").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("originalUrl", originalUrl))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.originalUrl").value(originalUrl));
        assertEquals(1, mappings.count());
        verifyNoInteractions(pageFetcher, policy, ai);
        assertTrue(pages.getAllServeEvents().isEmpty());
    }

    private void article(String html) {
        pages.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/article"))
                .willReturn(ok(html).withHeader("Content-Type", "text/html; charset=UTF-8")));
    }
}
