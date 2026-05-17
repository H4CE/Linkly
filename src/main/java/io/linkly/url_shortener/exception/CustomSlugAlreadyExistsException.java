package io.linkly.url_shortener.exception;

public class CustomSlugAlreadyExistsException extends RuntimeException {

    public CustomSlugAlreadyExistsException(String slug) {
        super("Custom slug already taken: " + slug);
    }

}