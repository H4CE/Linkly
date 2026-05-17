package io.linkly.url_shortener.util;

import org.springframework.stereotype.Component;

@Component
public class Base62Encoder {

    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final int BASE = ALPHABET.length(); // 62

    public String encode(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("ID must be positive, got: " + id);
        }

        StringBuilder sb = new StringBuilder();
        while (id > 0) {
            sb.append(ALPHABET.charAt((int) (id % BASE)));
            id /= BASE;
        }

        return sb.reverse().toString();
    }

    public long decode(String shortCode) {
        long result = 0;
        for (char c : shortCode.toCharArray()) {
            result = result * BASE + ALPHABET.indexOf(c);
        }
        return result;
    }

}