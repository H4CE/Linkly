package io.linkly.url_shortener.safety;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.linkly.url_shortener.exception.ApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPOutputStream;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PageContentFetcherTest {
    private static WireMockServer server;
    private PublicUrlPolicy policy;
    private SafetyProperties properties;
    private PageContentFetcher fetcher;
    private String root;

    @BeforeAll static void start() {
        server = new WireMockServer(wireMockConfig().dynamicPort().dynamicHttpsPort());
        server.start();
    }
    @AfterAll static void stop() { server.stop(); }
    @BeforeEach void setup() throws Exception {
        server.resetAll();
        properties = new SafetyProperties();
        properties.setMaxContentBytes(1024);
        policy = mock(PublicUrlPolicy.class);
        // Test-only substitution: production has no option to allow loopback destinations.
        // A fake hostname proves the HTTP client uses pinned DNS instead of resolving it again.
        when(policy.resolve(any())).thenAnswer(invocation -> new PublicUrlPolicy.Target(
                invocation.getArgument(0), new InetAddress[]{InetAddress.getByName("127.0.0.1")}));
        fetcher = new PageContentFetcher(policy, properties);
        root = "http://content.example.test:" + server.port();
    }
    @AfterEach void cleanup() { fetcher.close(); }

    @Test void fetchesPinnedHostWithoutSendingAuthenticationOrCookies() {
        server.stubFor(get(urlEqualTo("/article")).willReturn(ok("<p>A readable article.</p>")
                .withHeader("Content-Type", "text/html; charset=UTF-8")));
        var page = fetcher.fetch(root + "/article");
        assertEquals("<p>A readable article.</p>", new String(page.body(), StandardCharsets.UTF_8));
        server.verify(getRequestedFor(urlEqualTo("/article"))
                .withHeader("Host", equalTo("content.example.test:" + server.port()))
                .withHeader("Authorization", absent()).withHeader("Cookie", absent()));
        verify(policy, times(1)).resolve(URI.create(root + "/article"));
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void checksEachRedirectBeforeFollowingIt(int code) {
        server.stubFor(get(urlEqualTo("/start")).willReturn(aResponse().withStatus(code)
                .withHeader("Location", "/end").withHeader("Set-Cookie", "secret=not-forwarded")));
        server.stubFor(get(urlEqualTo("/end")).willReturn(ok("A readable final page.").withHeader("Content-Type", "text/plain")));
        var page = fetcher.fetch(root + "/start");
        assertEquals(URI.create(root + "/end"), page.finalUri());
        verify(policy).resolve(URI.create(root + "/end"));
        server.verify(getRequestedFor(urlEqualTo("/end")).withHeader("Cookie", absent()));
    }

    @Test void privateRedirectIsRejectedBeforeRequestingTheNextPage() {
        server.stubFor(get(urlEqualTo("/start")).willReturn(temporaryRedirect("http://127.0.0.1/private")));
        when(policy.resolve(URI.create("http://127.0.0.1/private"))).thenThrow(
                new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Private destination rejected"));
        assertEquals(400, assertThrows(ApiException.class, () -> fetcher.fetch(root + "/start")).getStatus().value());
        server.verify(1, getRequestedFor(urlEqualTo("/start")));
        assertEquals(1, server.getAllServeEvents().size());
    }

    @Test void productionPolicyBlocksLocalhost() {
        try (var localFetcher = new PageContentFetcher(new PublicUrlPolicy(), properties)) {
            assertEquals(400, assertThrows(ApiException.class,
                    () -> localFetcher.fetch("http://127.0.0.1/private")).getStatus().value());
            assertEquals(0, server.getAllServeEvents().size());
        }
    }

    @Test void redirectLoopsAreNotFollowedForever() {
        server.stubFor(get(urlEqualTo("/loop")).willReturn(temporaryRedirect("/loop")));
        assertEquals(422, assertThrows(ApiException.class, () -> fetcher.fetch(root + "/loop")).getStatus().value());
        server.verify(1, getRequestedFor(urlEqualTo("/loop")));
    }

    @Test void redirectLimitIsEnforced() {
        for (int i = 0; i < 5; i++) server.stubFor(get(urlEqualTo("/" + i)).willReturn(temporaryRedirect("/" + (i + 1))));
        assertEquals(422, assertThrows(ApiException.class, () -> fetcher.fetch(root + "/0")).getStatus().value());
        assertEquals(4, server.getAllServeEvents().size());
    }

    @Test void missingRedirectLocationIsUnscannable() {
        server.stubFor(get(urlEqualTo("/missing")).willReturn(aResponse().withStatus(302)));
        assertEquals(422, assertThrows(ApiException.class, () -> fetcher.fetch(root + "/missing")).getStatus().value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/octet-stream", "application/pdf", "image/png"})
    void unsupportedFilesAreNotApprovedAsPages(String type) {
        server.stubFor(get(urlEqualTo("/file")).willReturn(ok("binary data").withHeader("Content-Type", type)));
        assertEquals(422, assertThrows(ApiException.class, () -> fetcher.fetch(root + "/file")).getStatus().value());
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 429, 500, 503})
    void unreadableDestinationsDoNotSilentlyPass(int status) {
        server.stubFor(get(urlEqualTo("/unavailable")).willReturn(aResponse().withStatus(status)));
        assertEquals(422, assertThrows(ApiException.class, () -> fetcher.fetch(root + "/unavailable")).getStatus().value());
    }

    @Test void oversizedResponseIsRejected() {
        server.stubFor(get(urlEqualTo("/large")).willReturn(ok("a".repeat(2048)).withHeader("Content-Type", "text/html")));
        assertEquals(422, assertThrows(ApiException.class, () -> fetcher.fetch(root + "/large")).getStatus().value());
    }

    @Test void decompressedResponseMustFitTheLimitToo() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) { gzip.write("a".repeat(20_000).getBytes(StandardCharsets.UTF_8)); }
        assertTrue(bytes.size() < 1024);
        server.stubFor(get(urlEqualTo("/compressed")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withHeader("Content-Encoding", "gzip").withBody(bytes.toByteArray())));
        assertEquals(422, assertThrows(ApiException.class, () -> fetcher.fetch(root + "/compressed")).getStatus().value());
    }

    @Test void deadlineIncludesSlowResponseBodyRatherThanOnlyHeaders() {
        properties.setTimeoutSeconds(1);
        properties.setMaxContentBytes(8192);
        server.stubFor(get(urlEqualTo("/slow-body")).willReturn(ok("x".repeat(4096))
                .withHeader("Content-Type", "text/plain").withChunkedDribbleDelay(10, 4000)));
        assertTimeout(Duration.ofSeconds(4), () -> assertEquals(503,
                assertThrows(ApiException.class, () -> fetcher.fetch(root + "/slow-body")).getStatus().value()));
    }

    @Test void deadlineIncludesSlowResponseHeaders() {
        properties.setTimeoutSeconds(1);
        server.stubFor(get(urlEqualTo("/slow")).willReturn(ok("A page.")
                .withHeader("Content-Type", "text/html").withFixedDelay(2500)));
        assertTimeout(Duration.ofSeconds(4), () -> assertEquals(503,
                assertThrows(ApiException.class, () -> fetcher.fetch(root + "/slow")).getStatus().value()));
    }

    @Test void tlsCertificateVerificationRemainsEnabled() {
        server.stubFor(get(urlEqualTo("/tls")).willReturn(ok("A page.").withHeader("Content-Type", "text/html")));
        String untrusted = "https://content.example.test:" + server.httpsPort() + "/tls";
        assertEquals(503, assertThrows(ApiException.class, () -> fetcher.fetch(untrusted)).getStatus().value());
    }

    @Test void invalidContentTypeIsUnscannable() {
        server.stubFor(get(urlEqualTo("/type")).willReturn(ok("A page.").withHeader("Content-Type", "not a type")));
        assertEquals(422, assertThrows(ApiException.class, () -> fetcher.fetch(root + "/type")).getStatus().value());
    }

}
