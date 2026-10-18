# Linkly API reference

Examples match the current application code, including the URL-listing endpoint added in commit `cc4335f`.

For local startup, follow the [README](../README.md). On Windows, `start-linkly.cmd` generates the required credentials, writes `.env`, and starts the Docker stack. A Gemini key is optional for basic shortening.

## Base URL and request setup

```text
http://localhost:8080
```

For every POST request below, send this header and choose **Body -> raw -> JSON** in Postman:

```http
Content-Type: application/json
```

For protected endpoints, also send:

```http
Authorization: Bearer <JWT_FROM_LOGIN_OR_REGISTER>
```

In Postman's **Authorization -> Bearer Token** field, paste only the JWT. Postman adds `Bearer` automatically. For GET requests, choose **Body -> none**; do not send `{}`.

The AI creation flow uses two different tokens. Basic shortening needs only the JWT:

| Token | Where it comes from | Where it goes |
|---|---|---|
| JWT (`token`) | Login or registration response | `Authorization` header on protected requests |
| `verificationToken` | Suggestion or regeneration response | JSON body when regenerating or confirming |

Replace angle-bracket placeholders with actual values. Example AI slugs, metadata, and timestamps below are illustrative. Do not copy a placeholder verification token and expect it to validate.

The backend must be running with PostgreSQL available. Basic shortening uses Redis rate limiting but never fetches or assesses the destination; it does not call Gemini. Suggestion, regeneration and confirmation require Redis rate limiting and Gemini. Redirects use Redis caching with database fallback if Redis is unavailable. For Redis in your existing Docker Compose setup while Java runs on Windows, configure `localhost:6380`.

## Endpoint overview

| Method | Path | JWT required? | Request body | Success |
|---|---|---|---|---|
| POST | `/api/auth/register` | No | `email`, `password` | 201 |
| POST | `/api/auth/login` | No | `email`, `password` | 200 |
| GET | `/api/urls` | Yes | None | 200 |
| POST | `/api/urls/shorten` | Yes | `originalUrl` | 201 |
| POST | `/api/urls/suggest` | Yes | `originalUrl` | 200 |
| POST | `/api/urls/regenerate` | Yes | `originalUrl`, `previousSlug`, `verificationToken` | 200 |
| POST | `/api/urls/confirm` | Yes | `originalUrl`, `slug`, `verificationToken` | 201 |
| GET | `/api/urls/{shortCode}` | No | None | 302 |

Use **`/api/urls/suggest`**, not `/api/suggest`. There is no `/api/urls/shorten` endpoint or exposed custom-slug creation endpoint in the current code.

## 1. Register a user

```http
POST http://localhost:8080/api/auth/register
Content-Type: application/json
```

### Required JSON body

```json
{
  "email": "developer@example.com",
  "password": "LinklyDemo123!"
}
```

| Field | Validation |
|---|---|
| `email` | Required, nonblank, valid email format |
| `password` | Required, nonblank, at least 8 characters |

### Example response: 201 Created

```json
{
  "token": "<JWT>",
  "email": "developer@example.com",
  "message": "Registration successful"
}
```

Save `token` for protected requests. Registration already returns a JWT, so login is optional immediately afterward. Register once; use login for later sessions. Duplicate email registration returns 409 Conflict.

## 2. Log in

```http
POST http://localhost:8080/api/auth/login
Content-Type: application/json
```

### Required JSON body

```json
{
  "email": "developer@example.com",
  "password": "LinklyDemo123!"
}
```

Both fields are required and nonblank. Email must have a valid email format. Use the credentials of an existing registered account.

### Example response: 200 OK

```json
{
  "token": "<JWT>",
  "email": "developer@example.com",
  "message": "Login successful"
}
```

Wrong credentials return 401. JWT lifetime is configured by `app.jwt.expiry`; the sample configuration uses 24 hours.

## 3. List the logged-in user's saved URLs

```http
GET http://localhost:8080/api/urls
Authorization: Bearer <JWT>
```

**Request body: none.** No query parameters are required.

### Example response: 200 OK

```json
[
  {
    "shortUrl": "http://localhost:8080/api/urls/spring-boot-guide",
    "originalUrl": "https://example.com/articles/spring-boot?source=linkly",
    "createdAt": "2026-10-18T17:00:00",
    "expiresAt": null,
    "clickCount": 0,
    "tags": ["java", "spring"],
    "category": "Technology"
  }
]
```

- Returns only mappings owned by the authenticated user, newest first.
- Returns all matching entries in one array; pagination is not implemented.
- A user with no saved mappings receives `200 OK` and `[]`.
- No `userId` is accepted as an ownership selector. Adding another user's ID to a query does not change the authenticated owner.
- Listing does not generate AI output or increment clicks.

## Basic shortening without AI

```http
POST /api/urls/shorten
Authorization: Bearer <JWT_FROM_LOGIN_OR_REGISTER>
Content-Type: application/json
```

Required body:

```json
{
  "originalUrl": "https://example.com"
}
```

This validates HTTP(S) URL syntax and creates a random 10-character Base62 code without fetching the destination or performing any content/spam assessment. It needs no Gemini key, suggested slug or verification token. The normal database collision handling remains authoritative, so a collision can append an ID suffix. Example response (generated code and timestamp vary):

```json
{
  "shortUrl": "http://localhost:8080/api/urls/A7k2N9pQ4x",
  "originalUrl": "https://example.com",
  "createdAt": "2026-10-18T20:00:00",
  "expiresAt": null,
  "clickCount": 0,
  "tags": [],
  "category": "Uncategorized"
}
```

The mapping belongs to the logged-in user and appears in `GET /api/urls`. Open its returned `shortUrl` to use the public redirect. The independent basic-shortening quota is **30 attempts per user per hour**. Exceeding it returns 429 with an empty body. Redis rate limiting is still required; an unavailable limiter returns 503 before creation. Basic mode never fetches the destination.

### AI-only content assessment

Basic shortening performs no content or spam check. A successful basic response does not mean the URL is safe; it may point to an unavailable website, download or private address, provided its URL syntax is valid.

Only AI confirmation downloads HTML/plain text and passes up to 12,000 characters of prepared context to Gemini. That context includes extracted text plus HTML form/redirect/script excerpts. Gemini alone decides whether to approve or reject the content. Handwritten rules for password forms, wallet fields, suspicious scripts and file signatures have been removed.

For AI fetching, public HTTP(S) destinations on standard ports are required. DNS answers are validated and pinned, every HTTP redirect is validated, TLS verification stays enabled and caller credentials/cookies are not forwarded. Limits: 8 seconds total, 2-second connection/read timeouts, 3 HTTP redirects, 1 MiB decompressed body, 4 simultaneous fetches. `CONTENT_CHECK_TIMEOUT_SECONDS` changes the total deadline within 1-30 seconds. These are downloader safeguards, not local spam classification, and do not run in basic mode.

AI confirmation can return:

- **400:** an invalid HMAC pair, a blocked private/local fetch destination or unsupported fetch port, or Gemini rejecting the content.
- **422:** content cannot be prepared: unsupported file type, oversized/empty response, redirect loop/limit, unresolved hostname or unsuccessful HTTP response.
- **503:** fetching timed out/failed (including TLS failure), fetching is busy, Gemini is unavailable, or rate limiting is unavailable.

These content-related failures do not apply to basic shortening. AI approval is an imperfect assessment of downloaded excerpts at creation time; it does not guarantee safety. JavaScript is never executed, linked assets are not downloaded, and later changes are not covered. There is no automatic fallback from AI confirmation to unchecked basic creation; choose the basic endpoint explicitly.

PowerShell example using the `$baseUrl` and `$headers` from the login workflow below:

```powershell
$basicBody = @{ originalUrl = 'https://example.com' } | ConvertTo-Json
$basic = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/urls/shorten" `
    -Headers $headers -ContentType 'application/json' -Body $basicBody
$basic.shortUrl
```

## 4. Request an AI slug suggestion

```http
POST http://localhost:8080/api/urls/suggest
Authorization: Bearer <JWT>
Content-Type: application/json
```

### Required JSON body

```json
{
  "originalUrl": "https://example.com/articles/spring-boot?source=linkly"
}
```

`originalUrl` must be nonblank, start with `http://` or `https://`, and contain at most 2,048 characters. The implemented check is a prefix pattern, not a full URL/host validator.

### Example response: 200 OK

```json
{
  "originalUrl": "https://example.com/articles/spring-boot?source=linkly",
  "suggestedSlug": "spring-boot-guide",
  "suggestedShortUrl": "http://localhost:8080/api/urls/spring-boot-guide",
  "verificationToken": "<VERIFICATION_TOKEN_FROM_THIS_RESPONSE>"
}
```

Save `originalUrl`, `suggestedSlug`, and `verificationToken` together. They are needed for confirmation or regeneration. A suggestion does not save a URL mapping and does not reserve the slug.

The preview uses the actual `/api/urls/<slug>` route. It becomes active only after successful confirmation; a collision can change the final code. Use the returned confirmed `shortUrl`.

The current suggestion limit is **10 attempts per user per one-hour window**, beginning with the first request. Exceeding the limit returns 429 with an empty body. Requests that reach the limiter can consume an attempt even if subsequent AI work fails.

## 5. Regenerate a suggestion (optional)

```http
POST http://localhost:8080/api/urls/regenerate
Authorization: Bearer <JWT>
Content-Type: application/json
```

### Required JSON body

```json
{
  "originalUrl": "https://example.com/articles/spring-boot?source=linkly",
  "previousSlug": "spring-boot-guide",
  "verificationToken": "<VERIFICATION_TOKEN_FROM_PREVIOUS_SUGGESTION>"
}
```

| Field | What to send |
|---|---|
| `originalUrl` | The exact URL from the previous suggestion; same URL validation as suggest |
| `previousSlug` | The previous response's `suggestedSlug`; required and nonblank |
| `verificationToken` | The token returned for that exact URL and previous slug; required and nonblank |

### Example response: 200 OK

```json
{
  "originalUrl": "https://example.com/articles/spring-boot?source=linkly",
  "suggestedSlug": "spring-boot-notes",
  "suggestedShortUrl": "http://localhost:8080/api/urls/spring-boot-notes",
  "verificationToken": "<VERIFICATION_TOKEN_FROM_THIS_REGENERATED_RESPONSE>"
}
```

For confirmation, use the regenerated slug together with its corresponding token. Do not combine the new slug with the old token. You can regenerate again using this response as the previous suggestion.

Regeneration does not save a mapping. Its separate limit is **5 attempts per user per one-hour window**. Exceeding it returns 429 with an empty body. The backend verifies the slug changed; an unchanged or invalid provider response returns 503.

## 6. Confirm and save a short link

```http
POST http://localhost:8080/api/urls/confirm
Authorization: Bearer <JWT>
Content-Type: application/json
```

### Required JSON body

This example continues from the regenerated response above. If you skipped regeneration, use the original suggestion's slug and matching token instead.

```json
{
  "originalUrl": "https://example.com/articles/spring-boot?source=linkly",
  "slug": "spring-boot-notes",
  "verificationToken": "<VERIFICATION_TOKEN_FROM_REGENERATED_RESPONSE>"
}
```

| Field | Validation / source |
|---|---|
| `originalUrl` | Required, nonblank, HTTP(S) prefix, maximum 2,048 characters; preserve the exact suggested URL |
| `slug` | Copy `suggestedSlug`; required, nonblank, maximum 100 characters, letters/numbers/hyphens only |
| `verificationToken` | Copy the token for this exact URL/slug pair; required and nonblank |

Do not send `previousSlug` here: confirmation uses the field name `slug`. Do not send the login JWT as `verificationToken`. The JWT belongs in the Authorization header.

### Example response: 201 Created

```json
{
  "shortUrl": "http://localhost:8080/api/urls/spring-boot-notes",
  "originalUrl": "https://example.com/articles/spring-boot?source=linkly",
  "createdAt": "2026-10-18T17:05:00",
  "expiresAt": null,
  "clickCount": 0,
  "tags": ["java", "spring"],
  "category": "Technology"
}
```

Confirmation verifies the HMAC token, fetches the page, prepares text/HTML excerpts, asks Gemini to assess the downloaded context, generates metadata, and saves the mapping with its owner. Tags can fall back to `[]` and category to `Uncategorized`. The current request does not accept an expiry setting, so newly created mappings normally have `expiresAt: null`.

Invalid verification tokens, blocked fetch destinations and Gemini results interpreted as unsafe return 400. Unreadable AI-flow pages return 422, and fetch/network/deadline failures return 503. Gemini safety-check failure blocks creation with 503. Only an exact SAFE answer is accepted, ignoring case and outer whitespace; ambiguous responses such as NOT SAFE are rejected. Optional tagging failures fall back to empty tags and Uncategorized. If a slug is taken, creation appends a Base62 ID suffix, shortening the prefix if needed to keep the code within 100 characters. Each successful confirmation creates one mapping; conflicting writes are rolled back and retried up to three attempts. Repeated confirmation is not idempotent: it creates another link with a different code. Confirmation allows 10 attempted requests per user per hour; the independent suggest/regenerate quotas are 10 and 5. Counters include failed/rejected attempts after request-body validation. Quota exhaustion returns 429; unavailable Redis rate limiting returns 503 before Gemini is called.

## 7. Follow a saved short link

```http
GET http://localhost:8080/api/urls/spring-boot-notes
```

**Request body: none. JWT: not required.** Replace the example code with the code from an actual saved mapping, or simply open its returned `shortUrl` in a browser.

### Example response

```http
HTTP/1.1 302 Found
Location: https://example.com/articles/spring-boot?source=linkly
```

There is no JSON response body. Browsers normally follow the redirect automatically. In Postman, turn off automatic redirect following if you want to inspect the 302 and Location header rather than the destination site's response.

An unknown code returns 404. Every successful redirect increments the database count atomically, including cache hits and concurrent requests. Expired links return 410 Gone without incrementing, even when the destination is cached. Cache errors fall back to PostgreSQL. Each redirect requires a database write; counts represent requests, not unique visitors.

## 8. Health, metadata, and API documentation

All requests in this section are GET requests with **no body**.

| Full URL | JWT required? | Purpose |
|---|---|---|
| `http://localhost:8080/actuator/health` | No | Application/dependency health |
| `http://localhost:8080/actuator/info` | No | Configured application metadata |
| `http://localhost:8080/actuator/metrics` | Yes | Available metric names |
| `http://localhost:8080/actuator/loggers` | Yes | Logger configuration information |
| `http://localhost:8080/v3/api-docs` | No | OpenAPI JSON description |
| `http://localhost:8080/swagger-ui.html` | No | Interactive Swagger UI in a browser |

Actuator endpoints must also be enabled/exposed by configuration. The tracked default configuration exposes health, info and metrics; health details require authentication. Local property overrides may expose a different set. Health details and metrics vary with runtime state; an unavailable database or Redis may make the health endpoint report DOWN.

In Swagger UI, register or log in, select **Authorize**, paste the JWT, and execute a protected endpoint. The global Swagger security scheme can show authorization controls even for public operations; the security rules above describe actual access requirements.

## 9. Copyable PowerShell workflow

Run the following from Windows PowerShell after registering an account. Change the email/password to your existing account. The script carries the real verification token forward automatically.

```powershell
$baseUrl = 'http://localhost:8080'

# 1. Log in.
$loginBody = @{
    email = 'developer@example.com'
    password = 'LinklyDemo123!'
} | ConvertTo-Json

$auth = Invoke-RestMethod -Method Post `
    -Uri "$baseUrl/api/auth/login" `
    -ContentType 'application/json' `
    -Body $loginBody

$headers = @{ Authorization = "Bearer $($auth.token)" }

# 2. Suggest a slug. This does not save the link yet.
$suggestBody = @{
    originalUrl = 'https://example.com/articles/spring-boot?source=linkly'
} | ConvertTo-Json

$suggestion = Invoke-RestMethod -Method Post `
    -Uri "$baseUrl/api/urls/suggest" `
    -Headers $headers `
    -ContentType 'application/json' `
    -Body $suggestBody

$suggestion

# 3. Confirm using the actual URL, slug, and token returned above.
$confirmBody = @{
    originalUrl = $suggestion.originalUrl
    slug = $suggestion.suggestedSlug
    verificationToken = $suggestion.verificationToken
} | ConvertTo-Json

$saved = Invoke-RestMethod -Method Post `
    -Uri "$baseUrl/api/urls/confirm" `
    -Headers $headers `
    -ContentType 'application/json' `
    -Body $confirmBody

$saved

# 4. List your saved links. No request body is needed.
Invoke-RestMethod -Method Get `
    -Uri "$baseUrl/api/urls" `
    -Headers $headers

# Open the URL printed below in a browser to test the public redirect.
$saved.shortUrl
```

To regenerate before confirming, insert this optional block between steps 2 and 3. It replaces `$suggestion` with the new response, so step 3 uses the matching new slug and token:

```powershell
$regenerateBody = @{
    originalUrl = $suggestion.originalUrl
    previousSlug = $suggestion.suggestedSlug
    verificationToken = $suggestion.verificationToken
} | ConvertTo-Json

$suggestion = Invoke-RestMethod -Method Post `
    -Uri "$baseUrl/api/urls/regenerate" `
    -Headers $headers `
    -ContentType 'application/json' `
    -Body $regenerateBody
```

## 10. Common error responses and troubleshooting

### Missing authentication: 401

```json
{
  "status": 401,
  "error": "Authentication required"
}
```

Check the exact route and method, confirm Authorization is enabled for that request, and send `Bearer <JWT>`. Invalid/expired JWTs and tokens for deleted accounts return JSON with status 401 and error `Invalid or expired token`. A valid token on an unknown route preserves the original 404 during error dispatch.

### Request validation failure: 400

Example for an invalid URL:

```json
{
  "timestamp": "2026-10-18T17:10:00",
  "status": 400,
  "error": "Validation failed",
  "fields": {
    "originalUrl": "URL must be a valid http:// or https:// URL with a host"
  }
}
```

### Other common statuses

| Status | Typical current cause | What to check |
|---|---|---|
| 400 | Invalid URL/body, invalid HMAC pair, blocked AI fetch destination, or Gemini rejection | Use the exact URL, slug, and matching token from one suggestion response |
| 401 | Bad login credentials or missing/invalid JWT | Login credentials, Authorization header, fresh JWT |
| 404 | Unknown saved short code | Use a link that was successfully confirmed |
| 409 | Email already registered | Log in instead of registering again |
| 410 | Short link expired | Request a new link |
| 422 | AI-flow destination content could not be prepared | Use an accessible public HTML/plain-text page within the documented limits |
| 429 | Basic shortening or suggestion/regeneration/confirmation quota exceeded | Wait for that user's one-hour window to expire |
| 503 | Required AI operation, content fetch, or rate limiter unavailable | Destination connectivity/TLS, Gemini credentials/quota, Redis and scan capacity |
| 500 | Database/unexpected failure or exhausted slug-allocation retry | Application logs and dependency connectivity |

General runtime failures usually return:

```json
{
  "timestamp": "2026-10-18T17:10:00",
  "status": 500,
  "error": "Something went wrong"
}
```

If an AI endpoint returns 503 because the rate limiter is unavailable while Java runs on Windows and Redis runs through this project's Compose file, start Redis with `docker compose up -d redis`, verify `docker compose exec redis redis-cli ping` returns `PONG`, set `REDIS_HOST=localhost` and `REDIS_PORT=6380` for the Java process, and restart the application. A locally installed Redis service normally uses port 6379 instead.

Gemini credentials belong in backend configuration, not in your API request bodies. Provider requests have a five-second connection timeout and a configurable 15-second request deadline. Missing keys and invalid provider output return 503 for required AI operations. Tags/category are bounded to fit database columns. Basic shortening, registration, login, listing, and existing-link redirects make no Gemini calls; suggestion, regeneration, and confirmation depend on it. The tracked configuration allows startup without a Gemini key; database and signing settings remain required. See the [README](../README.md) for Docker and local startup.

Destinations must be valid HTTP(S) URLs with a host (maximum 2048 characters), with no embedded username/password or invalid port. Arbitrary custom slugs are not exposed through a direct shortening endpoint.
