package io.linkly.url_shortener.service;

import io.linkly.url_shortener.dto.request.RegisterRequest;
import io.linkly.url_shortener.exception.ApiException;
import io.linkly.url_shortener.exception.GlobalExceptionHandler;
import io.linkly.url_shortener.repository.UserRepository;
import io.linkly.url_shortener.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FailureHandlingTest {
    @Test void unavailableRedisBlocksAiRequestsWith503() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        var ex = assertThrows(ApiException.class, () -> new RateLimitService(redis).isConfirmAllowed(1L));
        assertEquals(503, ex.getStatus().value());
    }

    @Test void missingRedisScriptResultDoesNotBypassQuota() {
        var ex = assertThrows(ApiException.class, () -> new RateLimitService(mock(StringRedisTemplate.class)).isSuggestAllowed(1L));
        assertEquals(503, ex.getStatus().value());
    }

    @Test void concurrentDuplicateRegistrationReturns409() {
        var users = mock(UserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        when(users.existsByEmail("race@example.com")).thenReturn(false, true);
        when(encoder.encode(anyString())).thenReturn("hashed");
        when(users.save(any())).thenThrow(new DataIntegrityViolationException("unique constraint"));
        var service = new AuthService(users, encoder, mock(JwtUtil.class), mock(AuthenticationManager.class));
        var request = new RegisterRequest();
        request.setEmail("race@example.com"); request.setPassword("password123");
        var ex = assertThrows(ApiException.class, () -> service.register(request));
        assertEquals(409, ex.getStatus().value());
    }

    @Test void exceptionWithoutMessageStillProducesControlled500() {
        var result = new GlobalExceptionHandler().handleRuntimeException(new RuntimeException());
        assertEquals(500, result.getStatusCode().value());
        assertEquals("Something went wrong", result.getBody().get("error"));
    }
}
