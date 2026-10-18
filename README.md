# Linkly

Linkly is a URL shortener built with **Java 21, Spring Boot, PostgreSQL, and Redis**. It provides a REST API with JWT authentication and an interactive Swagger UI for trying it locally.

Users can:

- Create a short link immediately with a random code, without an AI key.
- Use Gemini to suggest readable slugs, assess destination content when confirming, and add tags and a category.
- View their saved original and short URLs, with click counts.
- Share public short links that redirect to the original destination.

**Basic shortening does not check destination content or classify spam.** The AI flow requires a working Gemini key; basic shortening, authentication, listing, and existing-link redirects work without one.

## Start locally on Windows

Install Docker Desktop with Linux containers enabled. Run the following from the folder containing `docker-compose.yml`, or double-click **`start-linkly.cmd`**:

```powershell
.\start-linkly.cmd
```

The script starts Docker Desktop if needed, generates a random database password and two independent signing secrets, writes them to `.env`, and builds and starts PostgreSQL, Redis, and Linkly. It waits for the application to become healthy, shows the Swagger address, and keeps the window open with live application logs. No local Java, Maven, PostgreSQL, Redis, or separate Ubuntu installation is required. The first build needs internet access.

**Rerunning the script preserves configured credentials and database data.** If an existing database is found but its password is missing, restore its original `DB_PASSWORD` in `.env` before rerunning. Keep `.env` private; it is excluded from Git. For one-shot startup without live logs or a closing pause, use `.\start-linkly.cmd --no-pause`.

### Try the application

Open [Swagger UI](http://localhost:8080/swagger-ui.html):

1. Register or log in and copy the returned JWT.
2. Click **Authorize** and enter the token.
3. Call `POST /api/urls/shorten` with `{"originalUrl":"https://example.com"}`.
4. Open the returned `shortUrl`, then call `GET /api/urls` to see your saved link and updated count.

For AI features, set `GEMINI_API_KEY` in `.env` and rerun the script. Then use **suggest → optionally regenerate → confirm**. The suggestion becomes a saved, active link only after confirmation.

### Manage the local stack

```powershell
docker compose logs -f app  # View application logs
.\stop-linkly.cmd           # Stop app, PostgreSQL and Redis; keep data
.\start-linkly.cmd          # Start again
```

Double-click `stop-linkly.cmd` or run it in another terminal. It stops only this project's containers, preserving `.env` and database storage. Docker Desktop stays running. Closing the startup window alone leaves the containers running; use the stop script to shut down the stack. The startup log window ends when the app stops.

Database data lives in a Docker volume, which can remain after containers and images are deleted. Keep the matching database password in `.env`; the interview guide covers existing-database detection and password recovery.

Default host ports: **8080** for Linkly, **5433** for PostgreSQL, and **6380** for Redis. Keep these available. An existing standalone container named `linkly-redis` must also be stopped and renamed before Compose can use that name; see the guide's troubleshooting section. Avoid `docker compose down -v` unless you intend to delete the database.

For manual Docker setup on another platform, copy `.env.example` to `.env`, replace `DB_PASSWORD`, `JWT_SECRET`, and `VERIFICATION_SECRET` with strong random values (at least 32 bytes per signing secret), and run `docker compose up --build -d`. The Gemini key is optional.

## More information

- [API reference](docs/API_REFERENCE.md): request bodies, responses, and copyable examples.
- [Interview guide](docs/LINKLY_INTERVIEW_GUIDE.md): architecture, design decisions, follow-up questions, testing, troubleshooting, and running Java without Docker.
