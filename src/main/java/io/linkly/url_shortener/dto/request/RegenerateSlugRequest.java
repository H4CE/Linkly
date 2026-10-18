package io.linkly.url_shortener.dto.request;

import jakarta.validation.constraints.NotBlank;
import io.linkly.url_shortener.validation.HttpUrl;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RegenerateSlugRequest {

    @NotBlank(message = "URL must not be blank")
    @Size(max = 2048, message = "URL must not exceed 2048 characters")
    @Pattern(
            regexp = "^(https?://).+",
            message = "URL must start with http:// or https://"
    )
    @HttpUrl
    private String originalUrl;

    @NotBlank(message = "Previous slug must not be blank")
    private String previousSlug;

    @NotBlank(message = "Verification token must not be blank")
    private String verificationToken;

}