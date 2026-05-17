package io.linkly.url_shortener.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Base62EncoderTest {

    private Base62Encoder base62Encoder;

    @BeforeEach
    void setUp() {
        base62Encoder = new Base62Encoder();
    }

    @Test
    @DisplayName("encode id 1 should return '1'")
    void encode_idOne_returnsOne() {
        String result = base62Encoder.encode(1L);
        assertEquals("1", result);
    }

    @Test
    @DisplayName("encode id 125 should return 'cb'")
    void encode_knownValue_returnsExpectedSlug() {
        String result = base62Encoder.encode(125L);
        assertEquals("21", result);
    }

    @Test
    @DisplayName("encode id 1000 should return 'g8'")
    void encode_largerValue_returnsExpectedSlug() {
        String result = base62Encoder.encode(1000L);
        assertEquals("g8", result);
    }

    @Test
    @DisplayName("decode should reverse encode correctly")
    void decode_encodedValue_returnsOriginalId() {
        long originalId = 1000L;
        String encoded = base62Encoder.encode(originalId);
        long decoded = base62Encoder.decode(encoded);
        assertEquals(originalId, decoded);
    }

    @Test
    @DisplayName("encode and decode should be symmetric for large ids")
    void encodeAndDecode_largeId_isSymmetric() {
        long originalId = 56800235584L;
        String encoded = base62Encoder.encode(originalId);
        long decoded = base62Encoder.decode(encoded);
        assertEquals(originalId, decoded);
    }

    @Test
    @DisplayName("encode should throw exception for zero")
    void encode_zero_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> base62Encoder.encode(0L));
    }

    @Test
    @DisplayName("encode should throw exception for negative id")
    void encode_negativeId_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> base62Encoder.encode(-1L));
    }

    @Test
    @DisplayName("different ids should produce different short codes")
    void encode_differentIds_produceDifferentCodes() {
        String code1 = base62Encoder.encode(1L);
        String code2 = base62Encoder.encode(2L);
        assertNotEquals(code1, code2);
    }

}