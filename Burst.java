/*
 * One-command on-sale stampede for the Seat Reservation service.
 *
 * Usage:
 *   java Burst.java <BASE_URL> [ADMIN_SECRET] [--quick]
 *
 * Examples:
 *   java Burst.java http://localhost:8080 --quick
 *   java Burst.java https://your-app.onrender.com admin-secret-change-me
 *
 * Requires Java 21+ (virtual threads). No build step, no dependencies: run the file directly.
 *
 * What it does, in order:
 *   0. Warm-up:            polls /readyz until the service is up (handles a cold start).
 *   A. General stampede:   ~20,000 concurrent reservations across a show with far fewer seats
 *                           than requests, so most requests MUST lose to a seat someone else won.
 *   B. Hot-seat storm:     500 distinct users all fight over exactly one seat.
 *   C. Per-user limit:     one user fires 10 parallel reserves on a show with per_user_limit=4.
 *   D. Idempotency:        each of N requests is sent twice, concurrently, with the SAME key;
 *                           plus a handful of "same key, different seats" conflict checks.
 *   E. Reconciliation:     GET /shows/{id} is checked against what this script itself observed,
 *                           and against the delta in /metrics taken before and after the run.
 *
 * Every phase prints the outcome distribution (confirmed / declined-by-reason / 5xx) and the
 * script exits non-zero if ANY invariant from the assignment is violated.
 */

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Burst {

    // ---------------------------------------------------------------- config

    static String baseUrl;
    static String adminSecret = "admin-secret-change-me";

    // "Full" sizing, roughly matching the assignment's ~20,000-concurrent-reservations bar.
    // --quick scales these down for a fast local sanity check before hammering a live free-tier host.
    static int stampedeRequests = 20_000;
    static int stampedeSeats = 2_000;
    static int hotSeatUsers = 500;
    static int limitParallelRequests = 10;
    static int idempotencyPairs = 300;

    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)   // follows /metrics -> /actuator/prometheus
            .connectTimeout(Duration.ofSeconds(10))
            // Force HTTP/1.1: against an HTTP/2 host (Render sits behind Cloudflare), the default
            // client multiplexes many virtual-thread requests as "streams" on one pooled connection
            // and hits the server's per-connection concurrent-stream cap under this kind of fan-out.
            // HTTP/1.1 gives each request its own connection, which is also closer to how a real
            // stampede of independent buyers' browsers actually behaves.
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    static boolean failed = false;
    // Phase D reuses fixed key strings so both concurrent attempts share one key WITHIN a run;
    // this makes them unique ACROSS separate runs too, so re-running the script against a live
    // deployment (same persistent DB) doesn't collide with idempotency rows an earlier run left behind.
    static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: java Burst.java <BASE_URL> [ADMIN_SECRET] [--quick]");
            System.exit(2);
        }
        baseUrl = stripTrailingSlash(args[0]);
        boolean quick = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--quick")) {
                quick = true;
            } else {
                adminSecret = args[i];
            }
        }
        if (quick) {
            stampedeRequests = 300;
            stampedeSeats = 50;
            hotSeatUsers = 50;
            limitParallelRequests = 10;
            idempotencyPairs = 25;
            System.out.println(">> --quick mode: running a small sanity burst, not the full ~20k stampede.");
        }

        section("TARGET");
        System.out.println("Base URL        : " + baseUrl);
        System.out.println("Stampede size    : " + stampedeRequests + " requests over " + stampedeSeats + " seats");
        System.out.println("Hot-seat storm   : " + hotSeatUsers + " users on 1 seat");
        System.out.println("Idempotency pairs: " + idempotencyPairs);

        warmUp();

        String adminToken = mintToken("admin-" + UUID.randomUUID(), adminSecret);
        String showId = createShow(adminToken);
        System.out.println("Show id          : " + showId);

        Map<String, Double> metricsBefore = fetchMetrics();

        Bucket phaseA = runGeneralStampede(showId);
        Bucket phaseB = runHotSeatStorm(showId);
        Bucket phaseC = runPerUserLimit(showId);
        Bucket phaseD = runIdempotency(showId);

        Map<String, Double> metricsAfter = fetchMetrics();

        reconcile(showId, phaseA, phaseB, phaseC, phaseD, metricsBefore, metricsAfter);

        section("RESULT");
        if (failed) {
            System.out.println("FAIL - one or more invariants were violated. See above.");
            System.exit(1);
        } else {
            System.out.println("PASS - no double-sell, zero 5xx, invariant held, idempotency and limits correct.");
        }
    }

    // ---------------------------------------------------------------- phases

    static void warmUp() throws Exception {
        section("PHASE 0: warm-up (/readyz)");
        Instant start = Instant.now();
        for (int attempt = 1; attempt <= 60; attempt++) {
            try {
                HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(baseUrl + "/readyz"))
                        .timeout(Duration.ofSeconds(5)).GET());
                if (r.statusCode() == 200) {
                    System.out.printf("Ready after %d attempt(s), %.1fs%n", attempt,
                            Duration.between(start, Instant.now()).toMillis() / 1000.0);
                    return;
                }
                System.out.println("  attempt " + attempt + ": HTTP " + r.statusCode() + ", retrying...");
            } catch (Exception e) {
                System.out.println("  attempt " + attempt + ": " + e.getClass().getSimpleName() + ", retrying...");
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Service never became ready at " + baseUrl + "/readyz");
    }

    static String createShow(String adminToken) throws Exception {
        section("SETUP: create show");
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= stampedeSeats; i++) labels.add("G" + i);
        labels.add("HOT1");
        for (int i = 1; i <= limitParallelRequests; i++) labels.add("LIMIT" + i);
        for (int i = 1; i <= idempotencyPairs; i++) labels.add("IDEM" + i);

        String seatsJson = labels.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",", "[", "]"));
        String body = "{\"name\":\"burst-" + System.currentTimeMillis()
                + "\",\"seats\":" + seatsJson + ",\"price_paise\":25000,\"per_user_limit\":4}";

        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(baseUrl + "/shows"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() != 201) {
            throw new IllegalStateException("show creation failed: " + r.statusCode() + " " + r.body());
        }
        return jsonString(r.body(), "id");
    }

    static Bucket runGeneralStampede(String showId) throws Exception {
        section("PHASE A: general stampede (" + stampedeRequests + " requests, " + stampedeSeats + " seats)");
        Bucket bucket = new Bucket();
        List<Callable<Void>> tasks = new ArrayList<>(stampedeRequests);
        for (int i = 0; i < stampedeRequests; i++) {
            final int n = i;
            tasks.add(() -> {
                String user = "stampede-" + n;
                String token = mintToken(user, null);
                String seat = "G" + (1 + (n % stampedeSeats));
                HttpResponse<String> r = reserve(showId, token, List.of(seat), UUID.randomUUID().toString());
                bucket.record(r);
                return null;
            });
        }
        runAll(tasks, "stampede");
        bucket.print();
        checkNo5xx(bucket, "Phase A (general stampede)");
        return bucket;
    }

    static Bucket runHotSeatStorm(String showId) throws Exception {
        section("PHASE B: hot-seat storm (" + hotSeatUsers + " users, 1 seat: HOT1)");
        Bucket bucket = new Bucket();
        List<Callable<Void>> tasks = new ArrayList<>(hotSeatUsers);
        for (int i = 0; i < hotSeatUsers; i++) {
            final int n = i;
            tasks.add(() -> {
                String token = mintToken("hotseat-" + n, null);
                HttpResponse<String> r = reserve(showId, token, List.of("HOT1"), UUID.randomUUID().toString());
                bucket.record(r);
                return null;
            });
        }
        runAll(tasks, "hot-seat storm");
        bucket.print();
        checkNo5xx(bucket, "Phase B (hot-seat storm)");
        long winners = bucket.count("201 confirmed");
        long losers = bucket.count("409 seat_taken");
        if (winners != 1) {
            fail("Phase B: expected exactly 1 winner on the hot seat, got " + winners);
        }
        if (winners + losers != hotSeatUsers) {
            fail("Phase B: winners+losers (" + (winners + losers) + ") != " + hotSeatUsers + " participants");
        }
        return bucket;
    }

    static Bucket runPerUserLimit(String showId) throws Exception {
        section("PHASE C: per-user limit (" + limitParallelRequests + " parallel reserves, one user, limit=4)");
        Bucket bucket = new Bucket();
        String token = mintToken("limit-tester", null);
        List<Callable<Void>> tasks = new ArrayList<>(limitParallelRequests);
        for (int i = 1; i <= limitParallelRequests; i++) {
            final String seat = "LIMIT" + i;
            tasks.add(() -> {
                HttpResponse<String> r = reserve(showId, token, List.of(seat), UUID.randomUUID().toString());
                bucket.record(r);
                return null;
            });
        }
        runAll(tasks, "per-user limit");
        bucket.print();
        checkNo5xx(bucket, "Phase C (per-user limit)");
        long confirmed = bucket.count("201 confirmed");
        if (confirmed > 4) {
            fail("Phase C: user ended up with " + confirmed + " seats, limit is 4");
        }
        return bucket;
    }

    static Bucket runIdempotency(String showId) throws Exception {
        section("PHASE D: idempotency (" + idempotencyPairs + " keys, each sent twice concurrently)");
        Bucket bucket = new Bucket();
        AtomicInteger sameKeyDifferentSeatsConflicts = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>(idempotencyPairs * 2 + 20);

        for (int i = 1; i <= idempotencyPairs; i++) {
            final String seat = "IDEM" + i;
            final String user = "idem-" + i;
            final String key = "idem-key-" + RUN_ID + "-" + i;
            for (int attempt = 0; attempt < 2; attempt++) {
                tasks.add(() -> {
                    String token = mintToken(user, null);
                    HttpResponse<String> r = reserve(showId, token, List.of(seat), key);
                    bucket.record(r);
                    return null;
                });
            }
        }
        // A handful of "same key, different seats" -> must be a clean 409 idempotency_conflict,
        // run AFTER the pairs above so the key already exists.
        runAll(tasks, "idempotency pairs");
        bucket.print();
        checkNo5xx(bucket, "Phase D (idempotency pairs)");

        List<Callable<Void>> conflictTasks = new ArrayList<>();
        int conflictSamples = Math.min(20, idempotencyPairs);
        for (int i = 1; i <= conflictSamples; i++) {
            final String user = "idem-" + i;
            final String key = "idem-key-" + RUN_ID + "-" + i;
            final String otherSeat = "IDEM" + ((i % idempotencyPairs) + 1);  // a different seat than key i's own
            conflictTasks.add(() -> {
                String token = mintToken(user, null);
                HttpResponse<String> r = reserve(showId, token, List.of(otherSeat), key);
                if (r.statusCode() == 409 && "idempotency_conflict".equals(jsonString(r.body(), "error"))) {
                    sameKeyDifferentSeatsConflicts.incrementAndGet();
                } else if (r.statusCode() >= 500) {
                    bucket.record(r);
                }
                return null;
            });
        }
        runAll(conflictTasks, "idempotency conflicts");
        System.out.println("same-key-different-seats -> 409 idempotency_conflict: "
                + sameKeyDifferentSeatsConflicts.get() + "/" + conflictSamples);
        if (sameKeyDifferentSeatsConflicts.get() != conflictSamples) {
            fail("Phase D: not every same-key-different-seats retry returned idempotency_conflict");
        }

        long winners = bucket.count("201 confirmed");
        long replays = bucket.count("201 replay");
        if (winners != idempotencyPairs) {
            fail("Phase D: expected " + idempotencyPairs + " originals (201), got " + winners);
        }
        if (replays != idempotencyPairs) {
            fail("Phase D: expected " + idempotencyPairs + " replays, got " + replays
                    + " (a retry must return the ORIGINAL reservation, not move anything extra)");
        }
        return bucket;
    }

    static void reconcile(String showId, Bucket a, Bucket b, Bucket c, Bucket d,
                          Map<String, Double> before, Map<String, Double> after) throws Exception {
        section("PHASE E: reconciliation");

        HttpResponse<String> show = send(HttpRequest.newBuilder(
                URI.create(baseUrl + "/shows/" + showId + "?include_seats=false"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + mintToken("reconciler", null))
                .GET());
        long available = jsonLong(show.body(), "available");
        long held = jsonLong(show.body(), "held");
        long confirmed = jsonLong(show.body(), "confirmed");
        long total = jsonLong(show.body(), "total_seats");

        System.out.printf("GET /shows/%s -> available=%d held=%d confirmed=%d total=%d%n",
                showId, available, held, confirmed, total);
        if (available + held + confirmed != total) {
            fail("Reconciliation invariant violated: available+held+confirmed (" + (available + held + confirmed)
                    + ") != total_seats (" + total + ")");
        } else {
            System.out.println("Invariant OK: available + held + confirmed == total_seats");
        }

        long expectedConfirmed = a.count("201 confirmed") + b.count("201 confirmed")
                + c.count("201 confirmed") + d.count("201 confirmed");
        System.out.println("Confirmed via API responses this run: " + expectedConfirmed
                + " (confirmed on show: " + confirmed + ", includes only THIS show's seats, matches 1:1 since "
                + "every phase targeted this one show)");
        if (expectedConfirmed != confirmed) {
            fail("Confirmed count from responses (" + expectedConfirmed + ") != confirmed seats on show (" + confirmed + ")");
        }

        System.out.println();
        System.out.println("Metrics delta (this run), compared against this script's own counts:");
        double confirmedDelta = delta(before, after, "reservations_confirmed_total", null);
        System.out.printf("  reservations_confirmed_total      delta=%.0f  expected=%d  %s%n",
                confirmedDelta, expectedConfirmed, confirmedDelta == expectedConfirmed ? "OK" : "MISMATCH");
        if (confirmedDelta != expectedConfirmed) {
            fail("reservations_confirmed_total did not reconcile with observed confirmations");
        }
        for (String reason : List.of("seat_taken", "per_user_limit", "idempotent_replay",
                "idempotency_conflict", "unknown_seat")) {
            double d2 = delta(before, after, "reservations_declined_total", reason);
            long expected = a.count("409 " + reason) + b.count("409 " + reason)
                    + c.count("409 " + reason) + d.count("409 " + reason)
                    + (reason.equals("idempotent_replay") ? d.count("201 replay") : 0);
            System.out.printf("  reservations_declined_total{%s} delta=%.0f  expected>=%d  %s%n",
                    reason, d2, expected, d2 >= expected ? "OK" : "MISMATCH");
        }
    }

    // ---------------------------------------------------------------- http helpers

    static HttpResponse<String> reserve(String showId, String token, List<String> seats, String idemKey)
            throws Exception {
        String seatsJson = seats.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",", "[", "]"));
        String body = "{\"seats\":" + seatsJson + ",\"idempotency_key\":\"" + idemKey + "\"}";
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/shows/" + showId + "/reserve"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    // Local cache so the same simulated user reuses one token instead of re-minting per request.
    static final Map<String, String> tokenCache = new ConcurrentHashMap<>();

    static String mintToken(String userId, String adminSecretOrNull) throws Exception {
        String cacheKey = userId + "|" + (adminSecretOrNull != null);
        String cached = tokenCache.get(cacheKey);
        if (cached != null) return cached;
        String body = adminSecretOrNull == null
                ? "{\"user_id\":\"" + userId + "\"}"
                : "{\"user_id\":\"" + userId + "\",\"admin_secret\":\"" + adminSecretOrNull + "\"}";
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(baseUrl + "/auth/token"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() != 200) {
            throw new IllegalStateException("token mint failed for " + userId + ": " + r.statusCode() + " " + r.body());
        }
        String token = jsonString(r.body(), "token");
        tokenCache.put(cacheKey, token);
        return token;
    }

    /**
     * One retry on a connection-level failure (reset, EOF, cold-start hiccup). Safe to retry here:
     * every reserve() call carries this script's own idempotency key, GETs are naturally safe, and
     * a token mint creating one extra identical-looking token is harmless. The only call where a
     * retry could theoretically duplicate work is show creation (used exactly once per run).
     */
    static HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        HttpRequest request = builder.build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            Thread.sleep(200);
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    static Map<String, Double> fetchMetrics() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(baseUrl + "/metrics")).timeout(REQUEST_TIMEOUT).GET());
        Map<String, Double> values = new ConcurrentHashMap<>();
        if (r.statusCode() != 200) {
            System.out.println("WARNING: /metrics returned " + r.statusCode() + ", reconciliation against metrics will be skipped");
            return values;
        }
        Pattern line = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{[^}]*\\})?\\s+([0-9.eE+-]+)$", Pattern.MULTILINE);
        Matcher m = line.matcher(r.body());
        while (m.find()) {
            String name = m.group(1);
            String labels = m.group(2) == null ? "" : m.group(2);
            double value = Double.parseDouble(m.group(3));
            values.put(name + labels, value);
        }
        return values;
    }

    static double delta(Map<String, Double> before, Map<String, Double> after, String metric, String reasonOrNull) {
        String suffix = reasonOrNull == null ? "" : "{reason=\"" + reasonOrNull + "\"}";
        double b = metricValue(before, metric, suffix);
        double a = metricValue(after, metric, suffix);
        return a - b;
    }

    static double metricValue(Map<String, Double> values, String metric, String suffix) {
        if (!suffix.isEmpty()) {
            Double exact = values.get(metric + suffix);
            if (exact != null) return exact;
        }
        // Label ordering in the exposition format isn't guaranteed, so fall back to a scan.
        double total = 0;
        boolean any = false;
        for (var e : values.entrySet()) {
            if (e.getKey().startsWith(metric + "{") || e.getKey().equals(metric)) {
                if (suffix.isEmpty() || e.getKey().contains(suffix.replace("{reason=", "reason=").replace("}", ""))) {
                    total += e.getValue();
                    any = true;
                }
            }
        }
        return any ? total : 0;
    }

    // ---------------------------------------------------------------- misc

    static void runAll(List<Callable<Void>> tasks, String label) throws Exception {
        Instant start = Instant.now();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = new ArrayList<>(tasks.size());
            for (Callable<Void> t : tasks) futures.add(pool.submit(t));
            int failures = 0;
            for (Future<Void> f : futures) {
                try {
                    f.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    failures++;
                    if (failures <= 3) {
                        System.out.println("  task failed: " + rootCause(e));
                    }
                }
            }
            if (failures > 0) {
                fail(label + ": " + failures + " requests threw (connection error / timeout), see above");
            }
        }
        double seconds = Duration.between(start, Instant.now()).toMillis() / 1000.0;
        System.out.printf("  %s: %d requests in %.1fs (%.0f req/s)%n", label, tasks.size(), seconds,
                tasks.size() / Math.max(seconds, 0.001));
    }

    static void checkNo5xx(Bucket bucket, String phaseName) {
        long serverErrors = bucket.count5xx();
        if (serverErrors > 0) {
            fail(phaseName + ": " + serverErrors + " requests got a 5xx (must be zero)");
        }
    }

    static void fail(String message) {
        failed = true;
        System.out.println("  !! FAIL: " + message);
    }

    static String rootCause(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " " + "=".repeat(Math.max(0, 70 - title.length())));
    }

    // ---------------------------------------------------------------- tiny JSON helpers (no deps)

    static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static long jsonLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!m.find()) throw new IllegalStateException("field '" + key + "' not found in: " + json);
        return Long.parseLong(m.group(1));
    }

    // ---------------------------------------------------------------- outcome bucket

    static final class Bucket {
        private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();
        private final List<String> sample5xx = new java.util.concurrent.CopyOnWriteArrayList<>();

        void record(HttpResponse<String> r) {
            counts.computeIfAbsent(classify(r), k -> new LongAdder()).increment();
            if (r.statusCode() >= 500 && sample5xx.size() < 3) {
                sample5xx.add(r.statusCode() + ": " + truncate(r.body(), 200));
            }
        }

        private static String classify(HttpResponse<String> r) {
            int status = r.statusCode();
            if (status == 201) {
                boolean replay = r.headers().firstValue("Idempotent-Replay").map("true"::equals).orElse(false);
                return replay ? "201 replay" : "201 confirmed";
            }
            String error = jsonString(r.body(), "error");
            return status + " " + (error != null ? error : "unknown");
        }

        long count(String key) {
            LongAdder a = counts.get(key);
            return a == null ? 0 : a.sum();
        }

        long count5xx() {
            return counts.entrySet().stream()
                    .filter(e -> e.getKey().matches("^5\\d\\d .*"))
                    .mapToLong(e -> e.getValue().sum()).sum();
        }

        void print() {
            counts.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> System.out.printf("  %-28s %d%n", e.getKey(), e.getValue().sum()));
            for (String s : sample5xx) System.out.println("  sample 5xx: " + s);
        }

        private static String truncate(String s, int n) {
            return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
        }
    }
}
