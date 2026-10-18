package io.linkly.url_shortener.safety;

import io.linkly.url_shortener.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentSafetyServiceTest {
    private final PageContentFetcher fetcher = mock(PageContentFetcher.class);
    private final ContentSafetyService safety = new ContentSafetyService(fetcher);
    private static final String URL = "https://example.com/article";

    private void page(String finalUrl, String html) {
        when(fetcher.fetch(URL)).thenReturn(new PageSnapshot(URI.create(finalUrl), "text/html; charset=UTF-8",
                html.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void readableArticleIsExtractedWithoutScriptsOrStyles() {
        page(URL, "<html><head><style>secret-style</style><script>throw new Error('must-not-run')</script></head>"
                + "<body><h1>Spring Boot</h1><p>A guide to building useful applications.</p>"
                + "<img src='https://unfetched.test/image.png'></body></html>");
        assertEquals("Spring Boot A guide to building useful applications.", safety.inspect(URL));
        verify(fetcher, times(1)).fetch(URL);
    }

    @Test void sameHostHttpsPasswordFormUsingPostIsAllowed() {
        page(URL, "<p>Sign in to your account.</p><form method='POST' action='/login'><input type='password'></form>");
        assertEquals("Sign in to your account.", safety.inspect(URL));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<form method='post' action='https://collector.test/login'><input type='password'></form>",
            "<form method='post' action='http://example.com/login'><input type='password'></form>",
            "<form method='get' action='/login'><input type='password'></form>",
            "<form action='/login'><input type='password'></form>",
            "<form method='post' action='javascript:steal()'><input type='password'></form>",
            "<base href='https://collector.test/'><form method='post' action='login'><input type='password'></form>",
            "<input type='password'>",
            "<form id='login' method='post' action='https://collector.test/login'></form><input type='password' form='login'>",
            "<form method='post'><input type='password'><button formaction='https://collector.test'>Submit</button></form>",
            "<input name='recovery_phrase'>", "<textarea id='mnemonic'></textarea>",
            "<input placeholder='Enter your private key'>",
            "<meta http-equiv='refresh' content='0;url=https://uninspected.test'>",
            "<script>eval(atob('YWxlcnQoMSk='))</script>"
    })
    void specificSuspiciousHtmlSignalsAreRejected(String html) {
        page(URL, "<p>This page has readable content.</p>" + html);
        assertEquals(400, assertThrows(ApiException.class, () -> safety.inspect(URL)).getStatus().value());
    }

    @Test void passwordCollectionOnHttpPageIsRejected() {
        page("http://example.com/login", "<form method='post'><p>Sign in</p><input type='password'></form>");
        assertEquals(400, assertThrows(ApiException.class, () -> safety.inspect(URL)).getStatus().value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "<html><body> </body></html>", "<script>renderApp()</script>"})
    void emptyOrScriptOnlyPagesAreUnscannable(String html) {
        page(URL, html);
        assertEquals(422, assertThrows(ApiException.class, () -> safety.inspect(URL)).getStatus().value());
    }

    @Test void ordinaryPlainTextIsSupported() {
        when(fetcher.fetch(URL)).thenReturn(new PageSnapshot(URI.create(URL), "text/plain; charset=UTF-8",
                "Public documentation in plain text.".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Public documentation in plain text.", safety.inspect(URL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MZ1234", "PK\u0003\u0004archive", "%PDF-1.0", "\u007fELFdata"})
    void binaryFilesDisguisedAsTextAreRejected(String binary) {
        when(fetcher.fetch(URL)).thenReturn(new PageSnapshot(URI.create(URL), "text/plain",
                binary.getBytes(StandardCharsets.ISO_8859_1)));
        assertThrows(ApiException.class, () -> safety.inspect(URL));
    }

    @Test void aiContextIsBoundedButLateHtmlStillGetsInspected() {
        page(URL, "<p>" + "a".repeat(15_000) + "</p>");
        assertEquals(12_000, safety.inspect(URL).length());
        page(URL, "<p>" + "a".repeat(15_000) + "</p><input name='seed_phrase'>");
        assertThrows(ApiException.class, () -> safety.inspect(URL));
    }
}
