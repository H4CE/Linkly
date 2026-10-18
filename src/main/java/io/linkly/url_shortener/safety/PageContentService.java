package io.linkly.url_shortener.safety;

import io.linkly.url_shortener.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Prepares downloaded content for Gemini; it makes no spam/safety classification itself. */
@Service
@RequiredArgsConstructor
public class PageContentService {
    private final PageContentFetcher fetcher;
    private static final int MAX_CONTEXT_LENGTH = 12_000;

    public String fetchForAi(String originalUrl) {
        PageSnapshot page = fetcher.fetch(originalUrl);
        MediaType type = MediaType.parseMediaType(page.contentType());
        String text;
        String structure = "";
        String scripts = "";
        if (type.getSubtype().equalsIgnoreCase("plain")) {
            text = new String(page.body(), type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset());
        } else {
            try {
                Document document = Jsoup.parse(new ByteArrayInputStream(page.body()),
                        type.getCharset() == null ? null : type.getCharset().name(), page.finalUri().toString());
                // Include structure which visible text alone would hide. Selecting these elements
                // only prepares evidence: no form, field name, redirect, or script triggers a local verdict.
                structure = document.select("form, input, textarea, meta[http-equiv]").outerHtml();
                scripts = document.select("script").outerHtml();
                document.select("script, style, noscript, template").remove();
                text = document.text();
            } catch (java.io.IOException ex) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Destination HTML could not be parsed");
            }
        }
        if (text.isBlank() && structure.isBlank() && scripts.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Destination returned no content for AI assessment");
        }
        // Bound context size/cost. The model receives excerpts, not the entire site or rendered browser state.
        String context = "Final fetched URL: " + limit(page.finalUri().toString(), 1000)
                + "\nPAGE TEXT:\n" + limit(text, 7000)
                + "\nHTML FORM/REDIRECT EXCERPTS:\n" + limit(structure, 2500)
                + "\nSCRIPT EXCERPTS (not executed):\n" + limit(scripts, 1000);
        return limit(context, MAX_CONTEXT_LENGTH);
    }

    private String limit(String value, int length) { return value.substring(0, Math.min(value.length(), length)); }
}
