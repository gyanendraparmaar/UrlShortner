# URL Shortener

A small Java service that stores long URLs in PostgreSQL, returns short Base62 codes, and permanently redirects those codes to their original URLs. The design follows the URL-shortener flow from Alex Xu's *System Design Interview*: a relational mapping table, unique numeric IDs converted to Base62, duplicate lookup before creation, and a read-oriented redirect endpoint.

## Requirements

- Java 21 or newer
- Docker with Docker Compose

Maven does not need to be installed; the repository includes the Maven wrapper.

## Install and run

Start PostgreSQL and wait for it to become healthy:

```bash
docker compose up -d --wait postgres
```

Start the application in a second terminal:

```bash
./mvnw spring-boot:run
```

The API is available at `http://localhost:8080`. Flyway creates the schema automatically on the first start, and the Docker volume preserves mappings across application and database restarts.

The defaults can be overridden with environment variables:

| Variable | Default |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/urlshortener` |
| `DATABASE_USERNAME` | `urlshortener` |
| `DATABASE_PASSWORD` | `urlshortener` |
| `BASE_URL` | `http://localhost:8080` |

## API

### Create a generated short code

```bash
curl -i -X POST http://localhost:8080/shorten \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/articles/42"}'
```

For a fresh database, the response is:

```http
HTTP/1.1 201 Created
Content-Type: application/json

{"code":"1000000","short_url":"http://localhost:8080/1000000","url":"https://example.com/articles/42","created":true}
```

### Create a custom alias

Custom aliases must contain 3–32 letters or digits.

```bash
curl -i -X POST http://localhost:8080/shorten \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/docs","custom_alias":"myDocs"}'
```

### Follow a short code

```bash
curl -i http://localhost:8080/1000000
```

Known codes return `301 Moved Permanently` with the original URL in the `Location` header. Unknown codes return a JSON `404 Not Found` response.

## Deliberate behavior

- Only absolute `http` and `https` URLs with a valid host are accepted. Credentials, whitespace, invalid ports, and normalized URLs longer than 2,048 characters are rejected.
- Scheme and host casing are normalized, and default ports (`80`/`443`) are removed. Paths, query strings, and fragments are preserved.
- Shortening the same normalized URL again without an alias returns the existing mapping with `200 OK` and `created: false`.
- Requesting the same alias for the same URL is also idempotent. Requesting a different alias for an already-shortened URL returns `409 Conflict` with its existing code.
- Reusing an alias for another URL returns `409 Conflict`; existing data is never overwritten.

## Why generated codes do not collide

PostgreSQL's sequence supplies a different numeric ID to every caller, including concurrent application instances. Base62 conversion is one-to-one, so distinct IDs produce distinct generated codes. The sequence starts at `62^6`, making generated codes seven characters long and giving the seven-character range roughly 3.5 trillion values, which covers the 365-billion-record estimate in Alex Xu's design.

The database also has unique constraints on both `short_code` and `long_url`. A custom alias can claim a value that a future Base62 ID would produce; if that happens, `INSERT ... ON CONFLICT DO NOTHING` prevents an overwrite and the generator advances to the next sequence value. The database constraint is therefore the final concurrency guard.

## Test

Make sure Docker is running, then execute:

```bash
./mvnw test
```

The suite includes service and controller tests plus Testcontainers integration tests against PostgreSQL 17. It covers Base62 boundaries, URL validation and normalization, generated and custom codes, duplicates, conflicting aliases, malformed JSON, 301 redirects, 404 responses, persistence, and a custom/generated code collision.

Build the runnable JAR with:

```bash
./mvnw verify
```

To stop the local database while retaining mappings:

```bash
docker compose stop postgres
```

To delete the local database volume and all mappings:

```bash
docker compose down -v
```

## Project structure

```text
src/main/java/.../urlshortener/       HTTP, service, and JDBC repository code
src/main/resources/db/migration/      PostgreSQL schema managed by Flyway
src/test/java/.../urlshortener/       Unit, API, and PostgreSQL integration tests
compose.yaml                          Local PostgreSQL configuration
AI_WRITEUP.md                         Required AI-use and trade-off reflection
```

Redis/Aerospike and Kafka are intentionally absent. At this assignment's scale they add failure modes and setup cost without improving correctness. At production read volume, a cache-aside layer could reduce redirect reads; Kafka would become useful if click events or other asynchronous consumers were introduced. See [AI_WRITEUP.md](AI_WRITEUP.md) for the full trade-off discussion.
