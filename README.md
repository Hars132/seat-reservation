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

(More sections - API, burst script, metrics, deploy - are added as the build progresses.)
