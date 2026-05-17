package io.linkly.url_shortener.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Component
public class SlugVerificationUtil {

    @Value("${app.verification.secret}")
    private String secret;

    private static final String ALGORITHM = "HmacSHA256";

    public String generateToken(String originalUrl, String slug) {
        try {
            String data = originalUrl + ":" + slug;
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(),
                    ALGORITHM
            );
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes());
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate verification token");
        }
    }

    public boolean verifyToken(String originalUrl, String slug, String token) {
        String expectedToken = generateToken(originalUrl, slug);
        return expectedToken.equals(token);
    }

}
