package io.linkly.url_shortener.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class SlugVerificationUtilTest {

    private SlugVerificationUtil slugVerificationUtil;

    private static final String TEST_SECRET = "testSecretForVerificationTesting123";
    private static final String TEST_URL = "https://www.baeldung.com/spring-boot";
    private static final String TEST_SLUG = "spring-boot-guide";

    @BeforeEach
    void setUp() {
        slugVerificationUtil = new SlugVerificationUtil();
        ReflectionTestUtils.setField(
                slugVerificationUtil,
                "secret",
                TEST_SECRET
        );
    }

    @Test
    @DisplayName("generateToken should return a non-null non-empty token")
    void generateToken_validInputs_returnsToken() {
        String token = slugVerificationUtil.generateToken(TEST_URL, TEST_SLUG);
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    @DisplayName("generateToken should return consistent token for same inputs")
    void generateToken_sameInputs_returnsConsistentToken() {
        String token1 = slugVerificationUtil.generateToken(TEST_URL, TEST_SLUG);
        String token2 = slugVerificationUtil.generateToken(TEST_URL, TEST_SLUG);
        assertEquals(token1, token2);
    }

    @Test
    @DisplayName("generateToken should return different tokens for different slugs")
    void generateToken_differentSlugs_returnsDifferentTokens() {
        String token1 = slugVerificationUtil.generateToken(TEST_URL, "slug-one");
        String token2 = slugVerificationUtil.generateToken(TEST_URL, "slug-two");
        assertNotEquals(token1, token2);
    }

    @Test
    @DisplayName("generateToken should return different tokens for different URLs")
    void generateToken_differentUrls_returnsDifferentTokens() {
        String token1 = slugVerificationUtil.generateToken(
                "https://google.com", TEST_SLUG);
        String token2 = slugVerificationUtil.generateToken(
                "https://youtube.com", TEST_SLUG);
        assertNotEquals(token1, token2);
    }

    @Test
    @DisplayName("verifyToken should return true for valid token")
    void verifyToken_validToken_returnsTrue() {
        String token = slugVerificationUtil.generateToken(TEST_URL, TEST_SLUG);
        assertTrue(slugVerificationUtil.verifyToken(TEST_URL, TEST_SLUG, token));
    }

    @Test
    @DisplayName("verifyToken should return false for tampered slug")
    void verifyToken_tamperedSlug_returnsFalse() {
        String token = slugVerificationUtil.generateToken(TEST_URL, TEST_SLUG);
        assertFalse(slugVerificationUtil.verifyToken(
                TEST_URL, "tampered-slug", token));
    }

    @Test
    @DisplayName("verifyToken should return false for tampered URL")
    void verifyToken_tamperedUrl_returnsFalse() {
        String token = slugVerificationUtil.generateToken(TEST_URL, TEST_SLUG);
        assertFalse(slugVerificationUtil.verifyToken(
                "https://evil.com", TEST_SLUG, token));
    }

    @Test
    @DisplayName("verifyToken should return false for fake token")
    void verifyToken_fakeToken_returnsFalse() {
        assertFalse(slugVerificationUtil.verifyToken(
                TEST_URL, TEST_SLUG, "completely-fake-token"));
    }

    @Test
    @DisplayName("verifyToken should return false for empty token")
    void verifyToken_emptyToken_returnsFalse() {
        assertFalse(slugVerificationUtil.verifyToken(
                TEST_URL, TEST_SLUG, ""));
    }

}