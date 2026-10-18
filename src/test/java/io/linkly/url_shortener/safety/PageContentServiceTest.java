package io.linkly.url_shortener.safety;

import io.linkly.url_shortener.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PageContentServiceTest {
    private final PageContentFetcher fetcher = mock(PageContentFetcher.class);
    private final PageContentService content = new PageContentService(fetcher);
    private static final String URL = "https://example.com/article";

    private void page(String html) {
        when(fetcher.fetch(URL)).thenReturn(new PageSnapshot(URI.create(URL), "text/html; charset=UTF-8",
                html.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void preparesTextAndStructuralEvidenceWithoutExecutingScripts() {
        page("<h1>Account page</h1><form action='https://collector.test' method='get'>"
                + "<input name='password' type='password'></form>"
                + "<script>throw new Error('must-not-run')</script><style>unrelated-style</style>");
        String context = content.fetchForAi(URL);
        assertTrue(context.contains("Account page"));
        assertTrue(context.contains("https://collector.test"));
        assertTrue(context.contains("type=\"password\""));
        assertTrue(context.contains("throw new Error('must-not-run')"));
        assertFalse(context.contains("unrelated-style"));
        verify(fetcher).fetch(URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<form action='https://collector.test' method='get'><input type='password'></form>",
            "<form action='http://example.com'><input type='password'></form>",
            "<input name='seed_phrase'>", "<textarea id='mnemonic'></textarea>",
            "<input placeholder='Enter your private key'>",
            "<meta http-equiv='refresh' content='0;url=https://elsewhere.test'>",
            "<script>eval(atob('YWxlcnQoMSk='))</script>"
    })
    void formerLocalSpamRulesNoLongerRejectContent(String html) {
        page(html);
        String context = assertDoesNotThrow(() -> content.fetchForAi(URL));
        assertTrue(context.contains("Final fetched URL: " + URL));
        // The context is handed to Gemini; the extraction service makes no SAFE/UNSAFE verdict.
        assertTrue(context.length() <= 12_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "<html><body> </body></html>"})
    void genuinelyEmptyPagesHaveNoEvidenceToAssess(String html) {
        page(html);
        assertEquals(422, assertThrows(ApiException.class, () -> content.fetchForAi(URL)).getStatus().value());
    }

    @Test void plainTextIsForwardedWithoutLocalSpamOrSignatureMatching() {
        when(fetcher.fetch(URL)).thenReturn(new PageSnapshot(URI.create(URL), "text/plain; charset=UTF-8",
                "MZ1234 Send money to claim a reward.".getBytes(StandardCharsets.UTF_8)));
        assertTrue(content.fetchForAi(URL).contains("MZ1234 Send money to claim a reward."));
    }

    @Test void boundsContextButKeepsFormEvidenceOutsideTheTextExcerpt() {
        page("<p>" + "a".repeat(15_000) + "</p><input name='seed_phrase'>");
        String context = content.fetchForAi(URL);
        assertTrue(context.length() <= 12_000);
        assertTrue(context.contains("seed_phrase"));
    }

    @Test void missingFetchIsNotInventedAsASafePage() {
        when(fetcher.fetch(URL)).thenThrow(new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Could not fetch destination content"));
        assertEquals(503, assertThrows(ApiException.class, () -> content.fetchForAi(URL)).getStatus().value());
    }
}
