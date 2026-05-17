package io.linkly.url_shortener.dto.request;

import jakarta.validation.constraints.NotBlank;
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
public class ConfirmShortenRequest {

    @NotBlank(message = "URL must not be blank")
    @Size(max = 2048, message = "URL must not exceed 2048 characters")
    @Pattern(
            regexp = "^(https?://).+",
            message = "URL must start with http:// or https://"
    )
    private String originalUrl;

    @NotBlank(message = "Slug must not be blank")
    @Size(max = 100, message = "Slug must not exceed 100 characters")
    @Pattern(
            regexp = "^[a-zA-Z0-9-]+$",
            message = "Slug can only contain letters, numbers and hyphens"
    )
    private String slug;

    @NotBlank(message = "Verification token must not be blank")
    private String verificationToken;

}