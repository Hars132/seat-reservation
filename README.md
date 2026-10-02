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

## Shows
```
# create (admin). per_user_limit is optional (default 4). Money is integer paise.
curl -s -X POST localhost:8080/shows -H "Authorization: Bearer <admin-token>" \
     -H 'Content-Type: application/json' \
     -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}'

# state (any authenticated user). ?include_seats=false returns counts only.
curl -s localhost:8080/shows/<id> -H "Authorization: Bearer <token>"
```
`GET /shows/{id}` returns `total_seats`, `available`, `held`, `confirmed` (also nested under `counts`) and `seats: [{label, status}]`.
`total_seats` is stored at creation, so `available + held + confirmed == total_seats` is a real reconciliation check.
Duplicate seat labels are rejected with 400; labels are `[A-Za-z0-9_.-]`, 1-32 chars; max 100,000 seats per show.

(More sections - API, burst script, metrics, deploy - are added as the build progresses.)
