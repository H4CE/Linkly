package io.linkly.url_shortener.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuggestResponse {

    private String originalUrl;
    private String suggestedSlug;
    private String suggestedShortUrl;
    private String verificationToken;

}