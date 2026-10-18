package io.linkly.url_shortener.util;

import org.springframework.stereotype.Component;
import java.security.SecureRandom;

@Component
public class RandomShortCodeGenerator {
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        var result = new StringBuilder(10);
        for (int i = 0; i < 10; i++) result.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        // Uniqueness is ultimately enforced by the existing database creation/retry service.
        return result.toString();
    }
}
