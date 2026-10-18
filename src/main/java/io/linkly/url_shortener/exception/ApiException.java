package io.linkly.url_shortener.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Expected API failures carry a status rather than relying on message matching. */
@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
