package io.linkly.url_shortener.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = HttpUrlValidator.class)
public @interface HttpUrl {
    String message() default "URL must be a valid http:// or https:// URL with a host";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
