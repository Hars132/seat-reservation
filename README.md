# Seat Reservation Service

Java 21 · Spring Boot 3 · PostgreSQL · Flyway · Micrometer/Prometheus

**Live URL:** https://seat-reservation-7lwe.onrender.com
(free tier - sleeps after ~15 min idle; cold start is ~85s, see WRITEUP.md)

**Full write-up:** see [WRITEUP.md](WRITEUP.md) for the atomic decision, idempotency design,
holds/expiry, the consistency-vs-availability tradeoff, observability, AI usage, and what's next.

## Quick verification (5 minutes, copy-paste)

Everything below runs against the **live URL** - no local setup needed. Replace `<ADMIN_SECRET>`
with the value shared separately. If the very first command (health check) is slow, the free-tier
instance was asleep; it will respond within ~90s and every command after that will be fast.

```bash
BASE=https://seat-reservation-7lwe.onrender.com
ADMIN_SECRET=<paste here>

# 1. Health - public, no token needed. (Hitting the bare URL "/" in a browser returns
#    401 unauthorized - that's correct: identity is token-derived, not an open root page.)
curl -s $BASE/healthz; echo
curl -s $BASE/readyz; echo              # {"db":"UP","status":"UP"} = DB reachable, fully ready

# 2. Mint an admin token (public endpoint)
ADMIN_TOKEN=$(curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' \
  -d "{\"user_id\":\"grader\",\"admin_secret\":\"$ADMIN_SECRET\"}" \
  | sed -E 's/.*"token":"([^"]+)".*/\1/')

# 3. Create a show (admin only)
curl -s -X POST $BASE/shows -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"verify","seats":["A1","A2","A3"],"price_paise":25000}'
# -> 201, id + 3 available seats. Copy the "id" for the next step.

# 4. Reserve a seat as a normal user
USER_TOKEN=$(curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' \
  -d '{"user_id":"buyer1"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -s -X POST $BASE/shows/<show-id>/reserve -H "Authorization: Bearer $USER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A1"],"idempotency_key":"k1"}'
# -> 201 confirmed. Retry the exact same command: returns the SAME reservation (idempotent replay).
```

**Or skip all of the above and run the one-command burst test** (needs Java 21+, no build step,
no dependencies - just run the file directly):
```bash
java Burst.java https://seat-reservation-7lwe.onrender.com <ADMIN_SECRET> --quick
```
This creates its own show, fires a few hundred concurrent requests across the hot-seat storm,
per-user-limit, and idempotency checks, then reconciles the result against the live API and
`/metrics`, and prints `PASS`/`FAIL`. Drop `--quick` for the full ~20,000-request stampede (takes
~20-25 min on the free tier; results from a full run are in WRITEUP.md).

**Metrics:** `curl -s $BASE/metrics` (public, Prometheus format)
**Logs:** Render's free tier has no public log sharing; see the screen recording link in the
submission email / in [WRITEUP.md](WRITEUP.md).

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

## Reserve
```
curl -s -X POST localhost:8080/shows/<id>/reserve -H "Authorization: Bearer <token>" \
     -H 'Content-Type: application/json' \
     -d '{"seats":["A12"],"idempotency_key":"order-1"}'
```
The idempotency key may be sent as header `Idempotency-Key` or body field `idempotency_key` (required, scoped per user).
- `201` reserved. `409 seat_taken`, `409 per_user_limit`, `409 idempotency_conflict`. `404 unknown_seat` / `show_not_found`. `400` invalid request.
- **Multi-seat requests are all-or-nothing**: you get every seat you asked for or none.
- **Per-user limit** (`per_user_limit`, default 4) is per show and holds under concurrency. It is checked before seat availability.
- **Idempotency**: the same key with the same seats (any order) returns the ORIGINAL response (same status and body) with header
  `Idempotent-Replay: true`, and changes nothing. The same key with different seats is `409 idempotency_conflict`.
  A declined attempt is remembered too: retrying that key replays the same decline. Use a new key to try again.
- Duplicate labels inside one request are rejected (400).
- Identity is the token's user; a `user_id` in the body is ignored.

## Cancel
```
curl -s -X POST localhost:8080/reservations/<reservation_id>/cancel -H "Authorization: Bearer <token>"
```
Only the owner may cancel (anyone else, or an unknown id, gets `404 reservation_not_found` - the API
never reveals that a reservation exists if it isn't yours). Cancelling an already-cancelled reservation
is `409 already_cancelled`. On success the seats return to `available` and the user's quota is released.
Matching is by `reservation_id`, not seat label, so a stale/late cancel can never release a seat that
has since been re-confirmed under a new reservation.

## Deploy, metrics & logs
Deployed on Render as a Docker web service with a managed Postgres instance in the same region.
Environment variables used: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_SECRET`,
`DB_POOL_SIZE`. `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true` is required on Render
specifically (the JVM otherwise stalls trying IPv6 first inside Render's container network).

- **Metrics:** `GET /metrics` (public, no auth) - Prometheus text format.
- **Health:** `GET /healthz` (liveness, never touches the DB) and `GET /readyz` (readiness, checks
  the DB and fails closed with 503 if it's unreachable).
- **Logs:** structured JSON with a `request_id` on every line, viewable in Render's dashboard under
  the service's Logs tab. Render's free tier does not offer public/unauthenticated log sharing, so
  as the brief's fallback option: **screen recording of the live logs under load** -
  https://youtu.be/ISrk7DaoZv0 (Render's Logs tab streaming in real time while `Burst.java --quick`
  runs against the live URL).

## Burst test (one command)
`Burst.java` reproduces the on-sale stampede against a live (or local) URL: a general stampede with
far more requests than seats, a 500-user hot-seat storm on one seat, a per-user-limit check, and an
idempotency check (every key sent twice, plus same-key/different-seats conflicts). It needs **Java 21+**
and nothing else - no build step, no dependencies.

```
java Burst.java <BASE_URL> [ADMIN_SECRET] [--quick]

# examples
java Burst.java http://localhost:8080 --quick                     # ~700 requests, fast local check
java Burst.java https://your-app.onrender.com admin-secret-change-me   # full ~20k-request stampede
```

It creates its own show, polls `/readyz` first (so it survives a cold start), prints the outcome
distribution for every phase (confirmed / declined-by-reason / 5xx), then reconciles three
independent views against each other: what the script itself observed, `GET /shows/{id}`, and the
delta in `/metrics` taken before and after the run. It exits non-zero if any invariant from the
assignment is violated (a seat double-sold, a 5xx, the per-user limit exceeded, a lost idempotent
replay, or a reconciliation mismatch).

(More sections - API, burst script, metrics, deploy - are added as the build progresses.)
