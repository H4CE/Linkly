package io.linkly.url_shortener.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional real-HTTPS check; the default suite never depends on an external website. */
@EnabledIfSystemProperty(named = "linkly.test.public-content", matches = "true")
class PublicContentSmokeTest {
    @Test void exampleDotComContentCanBePreparedForAi() {
        try (var fetcher = new PageContentFetcher(new PublicUrlPolicy(), new SafetyProperties())) {
            String content = new PageContentService(fetcher).fetchForAi("https://example.com");
            assertTrue(content.contains("Example Domain"));
        }
    }
}
