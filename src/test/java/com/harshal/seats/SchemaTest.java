package com.harshal.seats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class SchemaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;

    private UUID newShow() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shows(id, name, price_paise) VALUES (?, ?, ?)", id, "t", 25000L);
        return id;
    }

    @Test
    void migrationCreatesAllTables() {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
          + "AND table_name IN ('shows','seats','reservations','idempotency','user_show_quota')",
            Integer.class);
        assertEquals(5, n);
    }

    @Test
    void availableSeatWithNoOwner_isAccepted() {
        UUID show = newShow();
        assertEquals(1, jdbc.update(
            "INSERT INTO seats(show_id, label) VALUES (?, ?)", show, "A1"));
    }

    @Test
    void confirmedSeatWithoutOwner_isRejected() {
        UUID show = newShow();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO seats(show_id, label, status) VALUES (?, ?, 'confirmed')", show, "A1"));
    }

    @Test
    void unknownSeatStatus_isRejected() {
        UUID show = newShow();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO seats(show_id, label, status) VALUES (?, ?, 'sold')", show, "A1"));
    }

    @Test
    void duplicateSeatLabelInShow_isRejected() {
        UUID show = newShow();
        jdbc.update("INSERT INTO seats(show_id, label) VALUES (?, ?)", show, "A1");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO seats(show_id, label) VALUES (?, ?)", show, "A1"));
    }

    @Test
    void duplicateIdempotencyKeyForSameUser_isRejected() {
        jdbc.update("INSERT INTO idempotency(user_id, idem_key, request_hash) VALUES ('u1','k1','h')");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO idempotency(user_id, idem_key, request_hash) VALUES ('u1','k1','h')"));
    }
}
