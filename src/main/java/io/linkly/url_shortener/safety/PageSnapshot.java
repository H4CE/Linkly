package io.linkly.url_shortener.safety;

import java.net.URI;

/** The final response body is bounded before parsing; no browser or scripts are run. */
public record PageSnapshot(URI finalUri, String contentType, byte[] body) {}
