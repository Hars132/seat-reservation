# Seat Reservation Service

Java 21 · Spring Boot 3 · PostgreSQL · Flyway · Micrometer/Prometheus

## Run locally
```
docker compose up --build        # app on :8080, Postgres on :5432
curl localhost:8080/healthz      # liveness
curl localhost:8080/readyz       # readiness (checks DB)
```

Develop from VS Code: `docker compose up -d db`, then run `SeatApplication`.

## Test
```
mvn verify        # needs Docker running (Testcontainers)
```

## Auth
Identity is derived from the Bearer token only; any `user_id` in a request body is ignored.

```
# user token (any user id)
curl -s -X POST localhost:8080/auth/token -H 'Content-Type: application/json' \
     -d '{"user_id":"alice"}'

# admin token (needed for POST /shows). Default secret is admin-secret-change-me
# (override with ADMIN_SECRET; the JWT signing key with JWT_SECRET)
curl -s -X POST localhost:8080/auth/token -H 'Content-Type: application/json' \
     -d '{"user_id":"root","admin_secret":"admin-secret-change-me"}'

# what the server thinks you are
curl -s localhost:8080/auth/me -H "Authorization: Bearer <token>"
```

Every response carries `X-Request-ID` (a safe client-supplied value is honoured). Logs are JSON, one line per request, with `request_id` and `user_id`.

(More sections - API, burst script, metrics, deploy - are added as the build progresses.)
