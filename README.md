# Linkly — AI-Powered URL Shortener

A production-grade URL shortener built with **Spring Boot 3.5** and **Java 21**, featuring JWT authentication, Google Gemini AI integration, Redis caching, and full Docker support.

---

## Features

- **AI Slug Generation** — Google Gemini AI suggests meaningful, readable slugs for every URL
- **Spam Detection** — AI checks URLs for malicious or phishing content before saving
- **Auto-Tagging** — AI automatically assigns topic tags and categories to shortened URLs
- **Secure Flow** — HMAC-SHA256 verification tokens prevent client-side slug tampering
- **JWT Authentication** — Stateless auth with Spring Security filter chain
- **Redis Caching** — Redirect lookups cached in Redis for high performance
- **Rate Limiting** — Per-user Redis-backed rate limiting on AI endpoints
- **Observability** — Spring Boot Actuator health checks and metrics
- **API Docs** — Interactive Swagger UI with JWT support
- **Fully Containerised** — One-command startup with Docker Compose

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5 |
| Auth | Spring Security + JWT (jjwt) |
| ORM | Spring Data JPA + Hibernate |
| Database | PostgreSQL 16 |
| Cache | Redis 7 |
| AI | Google Gemini API |
| Docs | Swagger / OpenAPI 3 |
| Observability | Spring Boot Actuator |
| Testing | JUnit 5, Mockito, MockMvc, WireMock, H2 |
| Build | Maven |
| Deployment | Docker + Docker Compose |

---

## API Overview

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | Public | Register a new user |
| POST | `/api/auth/login` | Public | Login and get JWT token |
| GET | `/api/urls` | Protected | List the logged-in user's saved URLs, newest first |
| GET | `/api/urls/{shortCode}` | Public | Redirect to original URL |
| POST | `/api/urls/suggest` | Protected | AI generates slug suggestion |
| POST | `/api/urls/regenerate` | Protected | Get a new slug suggestion |
| POST | `/api/urls/confirm` | Protected | Spam check + save URL |
| GET | `/actuator/health` | Public | App health status |
| GET | `/actuator/info` | Public | App metadata |

### The three-step shortening flow

```
1. POST /api/urls/suggest    → AI generates slug, returns verificationToken
2. POST /api/urls/regenerate → Get new slug if unhappy (optional)
3. POST /api/urls/confirm    → Verify token + spam check + save to DB
```

### List your saved links

Send `GET /api/urls` with `Authorization: Bearer <your-jwt>` to retrieve all of
your saved mappings, newest first. Each entry includes the full `originalUrl`,
the working `shortUrl`, creation/expiry timestamps, click count, tags and category.
The user is determined from the JWT; a user ID is not accepted as an ownership
selector. Users with no saved mappings receive `200 OK` with an empty array.

---

## Getting Started

### Prerequisites

- Docker Desktop installed and running

That's it. No Java, PostgreSQL or Redis installation needed.

### Setup

**1. Clone the repository:**

```bash
git clone https://github.com/yourusername/linkly.git
cd linkly
```

**2. Create `application.properties` from the example:**

```bash
cp src/main/resources/application.properties.example src/main/resources/application.properties
```

Fill in your PostgreSQL password and Gemini API key.

**3. Create a `.env` file in the project root:**

```
GEMINI_API_KEY=your-gemini-api-key-here
```

Get a free Gemini API key at [aistudio.google.com](https://aistudio.google.com).

**4. Start the full stack:**

```bash
docker-compose up --build
```

This starts PostgreSQL, Redis, and the Spring Boot app — all connected and configured automatically.

**5. Open Swagger UI:**

```
http://localhost:8080/swagger-ui.html
```

---

## Running Tests

```bash
mvn test
```

**56 automated tests** covering:
- Unit tests — Base62 encoder, HMAC verification util
- Integration tests — Full HTTP flow via MockMvc against H2
- Security tests — JWT enforcement on protected endpoints
- URL listing tests — ownership isolation, empty lists, unchanged clicks, and usable short links
- AI service tests — Gemini API mocked with WireMock

---

## Project Structure

```
src/main/java/io/linkly/url_shortener/
├── controller/       # REST endpoints
├── service/          # Business logic + AI integration
├── repository/       # Spring Data JPA interfaces
├── entity/           # JPA entities (User, UrlMapping)
├── dto/              # Request and response objects
│   ├── request/
│   └── response/
├── exception/        # Custom exceptions + global handler
├── filter/           # JWT authentication filter
├── config/           # Security, Redis, Swagger config
└── util/             # Base62 encoder, JWT util, HMAC util
```

---

## Architecture Decisions

**Why Base62 encoding?**
Auto-incremented database IDs encoded in Base62 give guaranteed-unique, URL-safe short codes without randomness or collision risk. 6 characters → 56 billion combinations.

**Why a suggest → confirm flow?**
Separating suggestion from saving allows users to regenerate slugs without creating wasted DB entries. URLs are only saved when the user explicitly confirms.

**Why HMAC verification tokens?**
Prevents users from bypassing AI slug generation by sending arbitrary slugs directly to the confirm endpoint. The token cryptographically ties the originalUrl and slug together.

**Why Redis for caching?**
Redirects are the most frequent operation. Caching short code → original URL mappings in Redis eliminates repeated DB queries, reducing latency from ~20ms to ~1ms.

**Why stateless JWT?**
No server-side session storage needed. Every request is independently verifiable using the HMAC signature — scales horizontally without shared session state.

---

## Observability

```bash
# App health (DB + Redis status)
GET http://localhost:8080/actuator/health

# App metadata
GET http://localhost:8080/actuator/info

# Metrics (requires JWT)
GET http://localhost:8080/actuator/metrics
```

---

## Environment Variables

| Variable | Description | Default |
|---|---|---|
| `DB_HOST` | PostgreSQL host | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `url_shortener` |
| `DB_USERNAME` | PostgreSQL user | `postgres` |
| `DB_PASSWORD` | PostgreSQL password | — |
| `REDIS_HOST` | Redis host | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `JWT_SECRET` | JWT signing secret | — |
| `JWT_EXPIRY` | Token expiry in ms | `86400000` |
| `GEMINI_API_KEY` | Google Gemini API key | — |
| `APP_BASE_URL` | Base URL for short links | `http://localhost:8080` |
| `VERIFICATION_SECRET` | HMAC signing secret | — |

---

## License

MIT
