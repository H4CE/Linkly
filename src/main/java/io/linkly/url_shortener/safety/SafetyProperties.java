package io.linkly.url_shortener.safety;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "app.safety")
@Validated
@Getter
@Setter
public class SafetyProperties {
    @Min(1) @Max(30)
    private int timeoutSeconds = 8;

    @Min(1024) @Max(5_242_880)
    private int maxContentBytes = 1_048_576;

    @Min(0) @Max(5)
    private int maxRedirects = 3;
}
