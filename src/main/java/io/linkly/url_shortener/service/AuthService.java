package io.linkly.url_shortener.service;

import io.linkly.url_shortener.dto.request.LoginRequest;
import io.linkly.url_shortener.dto.request.RegisterRequest;
import io.linkly.url_shortener.dto.response.AuthResponse;
import io.linkly.url_shortener.entity.User;
import io.linkly.url_shortener.repository.UserRepository;
import io.linkly.url_shortener.util.JwtUtil;
import io.linkly.url_shortener.exception.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ApiException(HttpStatus.CONFLICT, "Email already registered");
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            // The unique constraint handles registrations racing after the pre-check.
            if (userRepository.existsByEmail(request.getEmail())) {
                throw new ApiException(HttpStatus.CONFLICT, "Email already registered");
            }
            throw ex;
        }

        String token = jwtUtil.generateToken(user.getEmail());

        return AuthResponse.builder()
                .token(token)
                .email(user.getEmail())
                .message("Registration successful")
                .build();
    }

    public AuthResponse login(LoginRequest request) {

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()
                )
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String token = jwtUtil.generateToken(user.getEmail());

        return AuthResponse.builder()
                .token(token)
                .email(user.getEmail())
                .message("Login successful")
                .build();
    }

}
