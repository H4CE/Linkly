package io.linkly.url_shortener.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.net.URI;

public class HttpUrlValidator implements ConstraintValidator<HttpUrl, String> {
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) return true; // @NotBlank reports this separately.
        try {
            URI uri = new URI(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank()
                    && uri.getRawUserInfo() == null && uri.getPort() >= -1 && uri.getPort() <= 65535;
        } catch (Exception ex) {
            return false;
        }
    }
}
