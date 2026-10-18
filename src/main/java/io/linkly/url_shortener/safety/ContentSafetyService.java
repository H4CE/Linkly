package io.linkly.url_shortener.safety;

import io.linkly.url_shortener.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ContentSafetyService {
    private final PageContentFetcher fetcher;
    private static final int MAX_AI_TEXT_LENGTH = 12_000;
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "seed[\\s_-]*phrase|recovery[\\s_-]*phrase|mnemonic|private[\\s_-]*key", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_SCRIPT = Pattern.compile(
            "(?:eval|Function)\\s*\\(\\s*(?:atob|unescape)\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** Passing these specific heuristics is not a guarantee that a website is safe. */
    public String inspect(String originalUrl) {
        PageSnapshot page = fetcher.fetch(originalUrl);
        rejectDisguisedBinary(page.body());
        MediaType type = MediaType.parseMediaType(page.contentType());
        String text;
        if (type.getSubtype().equalsIgnoreCase("plain")) {
            text = new String(page.body(), type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset());
        } else {
            try {
                Document document = Jsoup.parse(new ByteArrayInputStream(page.body()),
                        type.getCharset() == null ? null : type.getCharset().name(), page.finalUri().toString());
                inspectHtml(document, page.finalUri());
                document.select("script, style, noscript, template").remove();
                text = document.text();
            } catch (java.io.IOException ex) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Destination HTML could not be parsed");
            }
        }
        if (text.isBlank()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Destination has no readable text; script-only or empty pages cannot be checked");
        // Local rules inspect the full bounded document. Only the optional AI context is truncated.
        return text.substring(0, Math.min(text.length(), MAX_AI_TEXT_LENGTH));
    }

    private void inspectHtml(Document document, URI finalUri) {
        for (Element meta : document.select("meta[http-equiv]")) {
            if (meta.attr("http-equiv").equalsIgnoreCase("refresh"))
                throw flagged("Page contains an uninspected browser redirect");
        }
        for (Element script : document.select("script")) {
            if (OBFUSCATED_SCRIPT.matcher(script.data()).find())
                throw flagged("Page contains a flagged obfuscated-script pattern");
        }
        for (Element input : document.select("input, textarea")) {
            String description = input.attr("name") + " " + input.id() + " " + input.attr("placeholder");
            if (SECRET_FIELD.matcher(description).find())
                throw flagged("Page requests a wallet recovery phrase or private key");
            if (!input.attr("type").equalsIgnoreCase("password")) continue;
            Element form = input.hasAttr("form") ? document.getElementById(input.attr("form")) : input.closest("form");
            if (form == null || !form.tagName().equals("form"))
                throw flagged("Password collection has no inspectable form");
            if (!finalUri.getScheme().equalsIgnoreCase("https"))
                throw flagged("Page requests passwords over an unencrypted connection");
            URI action;
            try {
                action = form.attr("action").isBlank() ? finalUri : URI.create(form.absUrl("action"));
            } catch (IllegalArgumentException ex) { throw flagged("Password form has an invalid destination"); }
            if (!"https".equalsIgnoreCase(action.getScheme()) || action.getHost() == null
                    || !finalUri.getHost().equalsIgnoreCase(action.getHost())
                    || action.getRawUserInfo() != null || (action.getPort() != -1 && action.getPort() != 443))
                throw flagged("Password form sends credentials to an insecure or different host");
            if (!form.attr("method").equalsIgnoreCase("post"))
                throw flagged("Password form exposes credentials through its URL");
            // A submit button can override the form's action, method, or destination.
            for (Element control : document.select("button[formaction], input[formaction], button[formmethod], input[formmethod]")) {
                Element owner = control.hasAttr("form") ? document.getElementById(control.attr("form")) : control.closest("form");
                if (form.equals(owner)) throw flagged("Password form has a submission override that cannot be approved");
            }
        }
    }

    private void rejectDisguisedBinary(byte[] body) {
        if (body.length >= 4 && ((body[0] == 'M' && body[1] == 'Z')
                || (body[0] == 0x7f && body[1] == 'E' && body[2] == 'L' && body[3] == 'F')
                || (body[0] == 'P' && body[1] == 'K' && body[2] == 3 && body[3] == 4)
                || (body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F')))
            throw flagged("Destination contains a binary file rather than an inspectable page");
    }

    private ApiException flagged(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Content check rejected this URL: " + message);
    }
}
