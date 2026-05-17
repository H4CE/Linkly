package io.linkly.url_shortener.controller;

import io.linkly.url_shortener.service.AiService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiService aiService;

    // ── Register tests ────────────────────────────────────────────────

    @Test
    @DisplayName("register with valid credentials returns 201 and token")
    void register_validRequest_returns201WithToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "newuser@gmail.com",
                            "password": "password123"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.email").value("newuser@gmail.com"))
                .andExpect(jsonPath("$.message").value("Registration successful"));
    }

    @Test
    @DisplayName("register with duplicate email returns 500")
    void register_duplicateEmail_returnsError() throws Exception {
        // Register first time
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "duplicate@gmail.com",
                            "password": "password123"
                        }
                        """))
                .andExpect(status().isCreated());

        // Register second time with same email
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "duplicate@gmail.com",
                            "password": "password123"
                        }
                        """))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("register with invalid email returns 400")
    void register_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "not-an-email",
                            "password": "password123"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").exists());
    }

    @Test
    @DisplayName("register with short password returns 400")
    void register_shortPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "test@gmail.com",
                            "password": "short"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    @Test
    @DisplayName("register with blank email returns 400")
    void register_blankEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "",
                            "password": "password123"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").exists());
    }

    // ── Login tests ───────────────────────────────────────────────────

    @Test
    @DisplayName("login with valid credentials returns 200 and token")
    void login_validCredentials_returns200WithToken() throws Exception {
        // Register first
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "logintest@gmail.com",
                            "password": "password123"
                        }
                        """))
                .andExpect(status().isCreated());

        // Then login
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "logintest@gmail.com",
                            "password": "password123"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.email").value("logintest@gmail.com"))
                .andExpect(jsonPath("$.message").value("Login successful"));
    }

    @Test
    @DisplayName("login with wrong password returns 401")
    void login_wrongPassword_returns401() throws Exception {
        // Register first
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "wrongpass@gmail.com",
                            "password": "password123"
                        }
                        """))
                .andExpect(status().isCreated());

        // Login with wrong password
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "wrongpass@gmail.com",
                            "password": "wrongpassword"
                        }
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error")
                        .value("Invalid email or password"));
    }

    @Test
    @DisplayName("login with non-existent email returns 401")
    void login_nonExistentEmail_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "nobody@gmail.com",
                            "password": "password123"
                        }
                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("login with blank password returns 400")
    void login_blankPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "test@gmail.com",
                            "password": ""
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    // ── Security tests ────────────────────────────────────────────────

    @Test
    @DisplayName("register endpoint is public — no token needed")
    void register_noToken_isAccessible() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "public@gmail.com",
                            "password": "password123"
                        }
                        """))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("login endpoint is public — no token needed")
    void login_noToken_isAccessible() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "email": "anyone@gmail.com",
                            "password": "password123"
                        }
                        """))
                .andExpect(status().isUnauthorized());
    }

}