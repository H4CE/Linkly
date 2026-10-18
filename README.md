# Linkly — AI-powered URL shortener

Linkly is a Spring Boot 3.5 / Java 21 REST API with PostgreSQL persistence, JWT authentication, Gemini slug suggestions, HMAC verification, and Redis caching and rate limits.

## API

| Method | Route | Authentication | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | Public | Register and receive a JWT |
| POST | `/api/auth/login` | Public | Log in and receive a JWT |
| POST | `/api/urls/suggest` | Bearer JWT | Suggest a slug without saving |
| POST | `/api/urls/regenerate` | Bearer JWT | Replace a signed suggestion |
| POST | `/api/urls/confirm` | Bearer JWT | Verify, check safety, tag and save |
| GET | `/api/urls` | Bearer JWT | List your saved links, newest first |
| GET | `/api/urls/{shortCode}` | Public | Count the visit and return a 302 redirect |
| GET | `/actuator/health`, `/actuator/info` | Public | Health / metadata |
| GET | `/actuator/metrics` | Bearer JWT | Enabled metrics |

See [API_REFERENCE.md](API_REFERENCE.md) for request bodies, responses and a copyable PowerShell workflow. Swagger UI: [localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).

Shortening flow: **suggest → optionally regenerate → confirm**. Use the exact original URL, slug and verification token from the same response. A preview uses the real redirect path but becomes active only after confirmation. A collision may change the final code, so use the confirmed `shortUrl`.

## Run with Docker on Windows

Run these commands from the folder containing `pom.xml` and `docker-compose.yml`. Docker Desktop must be running; Java, Maven and a separate Linux distribution are unnecessary.

1. Copy `.env.example` to `.env`:
   ```powershell
   Copy-Item .env.example .env
   ```
2. Edit `.env`. Set the database password and **independent random** JWT / verification secrets, each at least 32 characters. Set your Gemini key to enable new-link AI features. One way to generate a secret in PowerShell:
   ```powershell
   $bytes = New-Object byte[] 48
   $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
   $rng.GetBytes($bytes)
   [Convert]::ToBase64String($bytes)
   $rng.Dispose()
   ```
   Run it separately for each secret.
3. Start:
   ```powershell
   docker compose up --build -d
   docker compose logs -f app
   ```
4. Open Swagger UI and try register → suggest → confirm → redirect → list.

Host ports: application **8080**, PostgreSQL **5433**, Redis **6380**. Containers communicate internally on 5432 / 6379. PostgreSQL data persists in the named volume.

**Existing installations:** `DB_PASSWORD` must match the password already stored in your PostgreSQL volume. Changing the variable alone does not change an existing database's password. Set the matching value; do not delete your data to fix a credentials mismatch. Changing signing secrets invalidates old JWTs / suggestion tokens.

Tracked `application.yaml` supplies the configuration for a clean clone. The Docker build excludes ignored local `application.properties` and `.env` files, so personal credentials are not bundled into the image. Compose supplies secrets at runtime.

## Run Java locally, without putting the app in Docker

Install Java 21 and PostgreSQL, create a database named `url_shortener`, and make Redis available. On Windows, Redis can run in Docker while Java runs directly on Windows:

```powershell
docker compose up -d redis
docker compose exec redis redis-cli ping
```

The last command should return `PONG`. Compose reads the project's `.env`, so configure it as above first. Alternatively, run a standalone Redis container on an unused port:

```powershell
docker run -d --name linkly-local-redis -p 127.0.0.1:6379:6379 redis:7-alpine
```

In the same PowerShell window used to start Java:

```powershell
$env:DB_HOST = 'localhost'
$env:DB_PORT = '5432'
$env:DB_NAME = 'url_shortener'
$env:DB_USERNAME = 'postgres'
$env:DB_PASSWORD = '<your-database-password>'
$env:REDIS_HOST = 'localhost'
$env:REDIS_PORT = '6380' # Use 6379 for the standalone Redis example.
$env:JWT_SECRET = '<your-random-secret-at-least-32-characters>'
$env:VERIFICATION_SECRET = '<another-random-secret-at-least-32-characters>'
$env:GEMINI_API_KEY = '<your-gemini-key>'
.\mvnw.cmd spring-boot:run
```

For an entirely non-Docker setup, use your installed PostgreSQL and a Redis service, for example Redis in an existing supported WSL Linux distribution. Set the corresponding host/ports. Docker Desktop's internal `docker-desktop` distribution is not a general-purpose Linux environment.

Spring Boot does **not** automatically load a project-root `.env` file when run directly. Export the variables above or configure the IDE's run environment. An existing ignored `src/main/resources/application.properties` takes precedence over YAML for matching keys; remove hardcoded overrides there or change them to environment placeholders. The `.sample` file shows optional overrides.

Build and run a JAR instead:

```powershell
.\mvnw.cmd clean package
java -jar target/url-shortener-0.0.1-SNAPSHOT.jar
```

## Correctness and failure behavior

- **Every successful redirect counts.** Only destination metadata is cached. An atomic SQL increment runs for each GET, including cache hits, preventing lost updates during concurrent visits. This is a count of redirect requests, including bots and repeated visits, rather than unique people.
- **Expiry applies on cache hits.** Expired links return **410 Gone** and do not increment. The SQL update also checks expiry, so a database expiry change takes effect even when Redis holds older metadata. Deleted links return **404**. There is currently no public expiry-editing endpoint.
- **Redis is optional for redirects.** Cache errors fall back to PostgreSQL; clicks are still counted. One database write per visit is intentional. Redis caches destination reads for up to one hour under the new `urlDestinations` namespace; older `urls` entries can expire naturally.
- **AI quotas:** per authenticated user, independent one-hour windows allow **10 suggest**, **5 regenerate**, and **10 confirm** requests. Redis Lua performs increment + TTL atomically and repairs old counters with no expiry. Attempted calls consume quota, including provider failures and rejected signed requests. Exceeding quota returns **429**. Unavailable rate limiting returns **503** before calling Gemini.
- **Gemini failures are bounded.** Connection timeout is 5 seconds; each request has a configurable 15-second deadline. Missing keys, provider HTTP errors, malformed responses and invalid generated slugs return **503** for required AI operations. Registration, login, listing and saved-link redirects do not call Gemini. The optional tagging step falls back to empty tags / `Uncategorized` if it fails after a successful safety check.
- **Conservative safety parsing:** only the complete response `SAFE` is accepted, ignoring case/outer whitespace. `NOT SAFE`, `SAFETY` and other ambiguous answers are rejected. AI checks are heuristic and cannot guarantee a destination is safe.
- **Untrusted input/output:** submitted destinations must parse as HTTP(S) URLs with a host and no embedded credentials; whitespace and invalid ports are rejected. Slugs and AI metadata are validated/bounded before persistence.
- **Consistent errors:** duplicate emails return **409**, invalid HMAC pairs / unsafe results **400**, malformed JSON **400**, invalid or deleted-user JWTs JSON **401**, and unexpected failures a generic **500**.
- **One row per confirmation.** Slug collisions update the same reserved mapping with an ID-based Base62 suffix. Unique-constraint conflicts roll back and retry in a fresh transaction, up to three attempts. Long slugs leave room for the suffix within 100 characters.

The redirect counter proves the server prepared a redirect after a successful database transaction; it cannot prove the browser followed the destination. A database outage still prevents redirects because reliable counting and mapping validity require PostgreSQL.

## Tests

```powershell
.\mvnw.cmd test
```

The default suite uses isolated H2 databases, an in-memory cache and a dynamic-port WireMock Gemini server. No real API key is required and no paid Gemini requests are made. Coverage includes authentication, ownership isolation, slug collisions, preview routes, cache-hit counting, concurrent clicks, expiry/deletion, cache failure fallback, malformed URLs, AI deadlines/output checks and dependency errors.

Real Redis / PostgreSQL tests are opt-in. Use **disposable** containers: the PostgreSQL test creates/drops its test schema. These tests must never point at an existing project database. For example:

```powershell
docker run -d --name linkly-test-redis -p 127.0.0.1:16379:6379 redis:7-alpine
docker run -d --name linkly-test-postgres -e POSTGRES_PASSWORD=isolated-test-only -e POSTGRES_DB=linkly_audit -p 127.0.0.1:15432:5432 postgres:16-alpine
# Wait for PostgreSQL to report ready.
docker exec linkly-test-postgres pg_isready -U postgres -d linkly_audit
.\mvnw.cmd "-Dlinkly.test.redis.port=16379" "-Dlinkly.test.postgres.port=15432" test
docker rm -f linkly-test-redis linkly-test-postgres
```

These checks use real HTTP requests and PostgreSQL atomic updates, Redis Lua quotas under concurrency, and serialization of cached expiry timestamps. The ordinary suite skips these opt-in tests when the properties are absent.

## Configuration

| Variable | Purpose | Default |
|---|---|---|
| `DB_HOST`, `DB_PORT` | Database address | localhost / 5432 |
| `DB_NAME`, `DB_USERNAME` | Database / user | url_shortener / postgres |
| `DB_PASSWORD` | Database password | Required |
| `REDIS_HOST`, `REDIS_PORT` | Redis address | localhost / 6379 |
| `JWT_SECRET` | JWT signing secret, at least 32 characters | Required |
| `JWT_EXPIRY` | JWT lifetime in milliseconds | 86400000 |
| `VERIFICATION_SECRET` | Independent HMAC signing secret | Required |
| `GEMINI_API_KEY` | Key for required AI operations | Empty; AI routes return 503 |
| `GEMINI_API_URL` | Gemini generateContent endpoint | gemini-2.5-flash-lite endpoint |
| `GEMINI_TIMEOUT_SECONDS` | Per-provider-request deadline | 15 |
| `APP_BASE_URL` | Public application origin used in generated links | http://localhost:8080 |

Compose uses its documented host mappings while overriding the app's internal addresses. Redis connect/command timeouts default to one second in the tracked YAML.

## Current limitations

There is no frontend, pagination, link editing/deletion API, refresh-token/logout-revocation flow, or database migration tool. Confirmation is not idempotent: replaying a valid confirmation can create another suffixed link within the quota. HMAC tokens bind the URL and slug but do not expire or bind to a particular user. Database timestamps use server-local time; deploy instances with consistent clocks/timezones. Cache invalidation would need extending before adding destination editing. Rate limits are per user; registration/login currently have no IP-based abuse protection. Hibernate `ddl-auto=update` is intended for development; use reviewed migrations before production.

## Code layout

`controller` defines routes, `service` handles auth/AI/creation/redirect lookup, `repository` performs persistence and atomic increments, `entity` defines users and mappings, `dto` defines payloads/cache metadata, `validation` checks destinations, `config` sets up security/cache/docs, and `util` contains JWT, HMAC and Base62 helpers.
