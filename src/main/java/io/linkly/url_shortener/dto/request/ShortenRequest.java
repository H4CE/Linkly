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
public class ShortenRequest {

    @NotBlank(message = "URL must not be blank")
    @Size(max = 2048, message = "URL must not exceed 2048 characters")
    @Pattern(
            regexp = "^(https?://).+",
            message = "URL must start with http:// or https://"
    )
    private String originalUrl;

    // Optional: user can request a custom slug e.g. "my-blog"
    // If null, we auto-generate one via Base62
    @Size(max = 20, message = "Custom slug must not exceed 20 characters")
    @Pattern(
            regexp = "^[a-zA-Z0-9-]*$",
            message = "Custom slug can only contain letters, numbers and hyphens"
    )
    private String customSlug;

}