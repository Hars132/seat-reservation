# WRITEUP.md — Seat Reservation at Scale

## The atomic decision

The entire correctness story comes down to one SQL statement in `ReservationService.CLAIM_SQL`:

```sql
WITH claimable AS (
    SELECT label FROM seats
    WHERE show_id = ? AND label = ANY(?) AND status = 'available'
    ORDER BY label
    FOR UPDATE
)
UPDATE seats s
   SET status = 'confirmed', reservation_id = ?, user_id = ?
  FROM claimable c
 WHERE s.show_id = ? AND s.label = c.label AND s.status = 'available'
RETURNING s.label
```

There is no separate "is this seat free?" read followed by a write. The `UPDATE`'s own `WHERE status = 'available'` *is* the check, executed as part of the same atomic write. Two transactions racing for seat A12 both try to lock that row; Postgres lets exactly one proceed, the other blocks, and when it wakes up it re-reads the committed row — which is now `confirmed` — so its `UPDATE` matches zero rows and it claims nothing. That transaction's `RETURNING` set comes back short, the code marks the transaction rollback-only, and the caller gets a clean `409 seat_taken`. No read-then-write window ever exists for a reader to act on stale information.

For multi-seat requests, every transaction locks the requested seats in **the same global order** — `ORDER BY label`. A deadlock requires a cycle (T1 holds X and waits for Y, while T2 holds Y and waits for X). With every transaction acquiring locks alphabetically, that cycle can't form: whichever transaction gets to the earlier label first simply makes the other one wait, never cross over. If `RETURNING` returns fewer seats than requested, the transaction is rolled back — I chose **all-or-nothing** for partial requests, since it's the only behavior that's simple to reason about and verify under concurrency; "best-effort" partial fills would need the same atomicity per seat and then a much harder policy question about what "partial success" even means to a paying customer.

I initially used `FOR UPDATE SKIP LOCKED`, which I abandoned. `SKIP LOCKED` treats a row that's merely locked by another in-flight transaction the same as a taken seat — which means three overlapping multi-seat requests could each skip past each other's momentarily-locked rows and all three come back empty, even though every seat involved is actually free. Plain `FOR UPDATE` with sorted locks guarantees that when seats genuinely are free, someone always wins.

## Idempotency

The idempotency key — accepted via the `Idempotency-Key` header or the `idempotency_key` body field — is stored in its own table, keyed `PRIMARY KEY (user_id, idem_key)`. Exactly-once is enforced in two layers:

1. **The database makes a duplicate key structurally impossible.** `INSERT ... ON CONFLICT (user_id, idem_key) DO NOTHING` either claims the key (returns 1 row) or discovers someone already owns it (returns 0). A concurrent duplicate request blocks on this insert's unique index until the first transaction commits, then sees the committed row and replays its result — there's no window where two requests both believe they're first.
2. **A savepoint, taken immediately after the key insert**, lets a later decline (seat taken, over limit) roll back everything *except* the key record. The key row is then updated to `outcome='declined'` with the reason, and the transaction commits anyway. This is what makes declines **sticky**: retrying the same key later replays the stored decline rather than being silently re-attempted, and the same key used with *different* seats returns `409 idempotency_conflict` even if the original attempt failed. Without the savepoint, a declined attempt would also erase its own key record, breaking that guarantee.

Same key + same seats (in any order — the request hash sorts seats before hashing) + same user → the original response, replayed, flagged with an `Idempotent-Replay: true` header. Same key + different seats → `409`. Different users can reuse the identical key string independently, since the key is scoped per user.

## Holds & expiry

I implemented the **explicit model**: a reservation is `confirmed` immediately on success, and `POST /reservations/{id}/cancel` is the only way a seat returns to `available`. There is no separate "held" state in this build — `held` is always reported as 0 in `GET /shows/{id}`, which is honest given the implementation rather than a stub value.

Cancel is made atomic the same way reserve is: `SELECT ... FOR UPDATE` locks the reservation row first (serializing a double-cancel race — the second caller waits, sees the row already `cancelled`, and gets a clean `409` instead of double-releasing seats), then release is matched **by `reservation_id`, not by seat label**. This matters: if release matched by label instead, a stale or retried cancel could flip a seat that's since been re-confirmed to someone else under a brand new reservation. Matching by id makes that structurally impossible — an old reservation's id never matches the new one's seats.

**What I'd do next:** a time-boxed hold (reserve → `held` with a TTL → auto-expire back to `available` if not confirmed) is the natural extension, and the schema already has a `held` status reserved for it. It would need a background sweep (or a lazy check-on-read) comparing `created_at` against a TTL, and the same atomic-conditional-update pattern extended with a third state transition.

## Consistency vs. availability under a partition

This system chooses **consistency**. Postgres is the single source of truth and there's exactly one writer; there's no multi-region replica to fail over to that could serve a plausible-but-possibly-stale answer. If the database is unreachable, `/readyz` returns `503` and **fails closed** — the app would rather refuse new reservations than risk a decision made without being able to verify current state. The Hikari pool has an explicit `connection-timeout`, and every connection carries a `lock_timeout` and `statement_timeout`, so a stuck dependency surfaces as a `503 temporarily_unavailable` rather than a hung request or, worse, a guess.

This is the only defensible choice for a seat sale: availability over consistency here would mean occasionally selling the same seat twice when a stale replica disagrees with reality, which directly violates the one invariant the whole exercise is built around.

## Live results (Render free tier)

Deployed at `https://seat-reservation-7lwe.onrender.com`. Two numbers worth stating plainly rather
than glossing over, both measured directly against the live deployment, not a local simulation:

- **Cold start: ~85 seconds** from a fully idle (15+ min) instance to `/readyz` returning
  `200 {"db":"UP","status":"UP"}` on the first request, no retries needed. The app comes up healthy
  every time I've tested it; `/readyz` never once returned a false "up" before the DB was actually
  reachable, and never leaves the caller guessing - it simply fails closed with `503` until the
  database is confirmed reachable, then flips to `200`.
- **Sustained throughput: ~15 req/s** for the full `Burst.java` stampede (20,000 reserve requests
  plus token minting, paced at 100 in-flight) over roughly 22 minutes, with **zero 5xx** the entire
  run. That ceiling is the free tier's single shared vCPU, not the application logic - the same
  Docker image handles the identical local load at 200-400+ req/s against Docker Desktop on a
  laptop. I'm stating this plainly rather than letting a passing burst run imply this setup is
  production-ready as deployed: a real on-sale event would need more than a free-tier single-core
  box to absorb a genuine simultaneous stampede.

The full (non-`--quick`) burst result against the live URL: 20,000 requests over 2,000 seats
(exactly 2,000 confirmed, 18,000 clean `409 seat_taken`), a 500-user hot-seat storm (exactly 1
winner), a 10-parallel-request per-user-limit check (capped at exactly 4), 300 idempotency pairs
(300 originals + 300 replays, 20/20 same-key-different-seats conflicts correctly rejected), and a
reconciliation that matched the script's own request counts, `GET /shows/{id}`, and the `/metrics`
deltas to the unit. The one honest caveat: at this throughput ceiling, the end-to-end run took
roughly 22 minutes to push all 20,000 requests through - correctness held throughout, but it's
worth being upfront that "zero 5xx under load" here means zero 5xx at ~15 req/s sustained, not at
whatever instantaneous arrival rate a real on-sale moment might produce against this specific host.

## Observability — what I'd get paged for at 2am

- **Any 5xx rate above zero**, sustained for more than a few seconds. The design goal is that declines are always a clean 4xx; a 5xx means something is actually broken, not just contested.
- **Readiness flapping** (`/readyz` toggling 200/503 repeatedly) — usually a DB connectivity problem or pool exhaustion.
- **`seats_available` gauge disagreeing with `reservations_confirmed_total` + `reservations_declined_total`** in a way that doesn't reconcile — this is the signal that the core invariant itself might be at risk, which is the most serious possible alert this system can raise.
- **Hikari pool saturation** (connections pending > 0 for a sustained period) — an early warning before it turns into visible 503s.
- **p99 latency** climbing on `/shows/{id}/reserve` specifically, since that's the path under the most contention.

Metrics are exposed at `/metrics` (Prometheus text format) and deliberately left **public**, unauthenticated, same as `/healthz` and `/readyz` — a monitoring system should never need application credentials to answer "is this alive." `reservations_confirmed_total` and `reservations_declined_total{reason}` are incremented only after the owning transaction has actually committed, so they can never show a count the database doesn't back up. `seats_available{show}` is evaluated **live, at scrape time** — not cached or pushed — specifically so it can never drift from the database between scrapes. Logs are structured JSON with a `request_id` (client-supplied if present and safe, generated otherwise) carried through every log line for a given request via MDC, which is what you'd actually grep on when chasing one specific failed reservation.

**A known limitation to flag honestly:** the `seats_available` gauge is registered per-show, so its cardinality grows with the number of shows ever created. Fine for this exercise and for a single on-sale event; a long-lived production deployment would want to expire gauges for shows that have ended.

## AI usage — directed vs. decided

I used Claude throughout this build, and I want to be specific about the division of labor rather than vague about it, because I'll be asked to extend this live.

**Decisions that were mine, made deliberately before any code was written:**
- Java/Spring Boot as the stack (my strongest language — I wanted to defend it, not learn a new one under time pressure).
- PostgreSQL over MySQL, specifically for `RETURNING`, `ON CONFLICT`, and more predictable locking semantics under `READ COMMITTED`.
- `JdbcTemplate` over JPA/Hibernate — the whole exercise is about the exact SQL statement doing the atomic work, and an ORM would hide or second-guess exactly that.
- The explicit-cancel hold model over time-boxed holds, as the simpler correct baseline for the time available.
- All-or-nothing for partial multi-seat requests.
- Dropping `SKIP LOCKED` in favor of plain `FOR UPDATE` with sorted locks, after reasoning through the overlapping-multi-seat failure mode it could cause.
- The savepoint design for sticky idempotent declines.

**What I directed AI to do, and reviewed:** generating the Spring Boot boilerplate (controllers, DTOs, exception mapping), writing the concurrency test suite (`ExecutorService`/`CountDownLatch` harnesses for the hot-seat and overlapping-multi-seat tests), drafting the Dockerfile and `docker-compose.yml`, and writing `Burst.java`.

**Where AI genuinely caught real bugs I'd have otherwise shipped or debugged slower:**
- An HTTP/2 "too many concurrent streams" error against Render's Cloudflare front end, traced to the JDK `HttpClient`'s default protocol multiplexing many virtual-thread requests onto one connection.
- A client-side ephemeral-port exhaustion (`BindException`) caused by *fixing* the above by forcing HTTP/1.1, which then meant 20,000 virtual threads each tried to open their own fresh connection at once — fixed by bounding actual in-flight network calls with a semaphore, independent of how many logical requests are queued.
- A test-suite bug where sharing one Testcontainers Postgres instance across test classes (for speed) meant idempotency keys reused across test methods collided with each other and produced confusing false failures — fixed with a `@BeforeEach TRUNCATE`.
- A Spring Actuator configuration property (`path-mapping`) that's silently defunct in modern Spring Boot, which I'd set without realizing it does nothing — causing `/metrics` to 404 until replaced with a working redirect to the real endpoint.

In each of these cases I didn't just accept a patch — I asked *why* the failure happened before applying a fix, which is the standard I'd hold myself to live in the interview too.

## What I'd do next

1. **Time-boxed holds** with a TTL and expiry sweep, as the natural extension of the current explicit-cancel model — the schema already anticipates it.
2. **A real identity provider** in place of the exercise's open token-minting endpoint (`POST /auth/token` currently mints a USER token for any user id on request, which is deliberate for load-testing but not how real auth would work).
3. **Horizontal read scaling** for `GET /shows/{id}` under extremely high poll rates during an on-sale event, since every write still needs to go through the single Postgres writer regardless.
4. **Gauge cardinality cleanup** for `seats_available`, expiring metrics for shows that have concluded.
5. **A real load generator run from multiple machines**, not one laptop — `Burst.java`'s own client-side concurrency limits (ephemeral ports, HTTP/2 stream caps) are themselves evidence that a single machine isn't a perfect stand-in for a real distributed stampede of buyers.
