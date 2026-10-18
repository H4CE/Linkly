package io.linkly.url_shortener.dto;

import java.time.LocalDateTime;

/** Cache only destination metadata; click counts must always come from the database. */
public record RedirectTarget(Long id, String originalUrl, LocalDateTime expiresAt) {}
