package com.harshal.seats.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

    private record ShowRow(String name, long pricePaise, int perUserLimit, int totalSeats) {}
    private record StatusCount(String status, int n) {}

    // One statement inserts every seat (works for 50k+ seats), in the order supplied.
    private static final String INSERT_SEATS = """
        INSERT INTO seats(show_id, label, position)
        SELECT CAST(? AS uuid), t.label, (t.ord - 1)::int
        FROM unnest(CAST(? AS text[])) WITH ORDINALITY AS t(label, ord)
        """;

    private final JdbcTemplate jdbc;

    public ShowService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Show row and all seats are created in one transaction: a show never exists half-built. */
    @Transactional
    public ShowView create(String name, List<String> labels, long pricePaise, int perUserLimit) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO shows(id, name, price_paise, per_user_limit, total_seats) VALUES (?, ?, ?, ?, ?)",
            id, name, pricePaise, perUserLimit, labels.size());
        String[] arr = labels.toArray(new String[0]);
        jdbc.update(INSERT_SEATS, ps -> {
            ps.setObject(1, id);
            ps.setArray(2, ps.getConnection().createArrayOf("text", arr));
        });
        List<SeatView> seats = labels.stream().map(l -> new SeatView(l, "available")).toList();
        return ShowView.of(id.toString(), name, pricePaise, perUserLimit,
                labels.size(), labels.size(), 0, 0, seats);
    }

    /**
     * The counts always come from ONE statement (one snapshot), so available + held + confirmed
     * is internally consistent even while reservations commit concurrently. total_seats is stored
     * separately at creation, which is what makes the reconciliation a genuine check.
     */
    public Optional<ShowView> find(UUID id, boolean includeSeats) {
        List<ShowRow> rows = jdbc.query(
            "SELECT name, price_paise, per_user_limit, total_seats FROM shows WHERE id = ?",
            (rs, i) -> new ShowRow(rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getInt(4)), id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ShowRow show = rows.get(0);

        int available = 0, held = 0, confirmed = 0;
        List<SeatView> seats = null;

        if (includeSeats) {
            seats = jdbc.query(
                "SELECT label, status FROM seats WHERE show_id = ? ORDER BY position, label",
                (rs, i) -> new SeatView(rs.getString(1), rs.getString(2)), id);
            for (SeatView s : seats) {
                switch (s.status()) {
                    case "available" -> available++;
                    case "held" -> held++;
                    case "confirmed" -> confirmed++;
                    default -> throw new IllegalStateException("unknown seat status " + s.status());
                }
            }
        } else {
            List<StatusCount> counts = jdbc.query(
                "SELECT status, count(*) FROM seats WHERE show_id = ? GROUP BY status",
                (rs, i) -> new StatusCount(rs.getString(1), rs.getInt(2)), id);
            for (StatusCount c : counts) {
                switch (c.status()) {
                    case "available" -> available = c.n();
                    case "held" -> held = c.n();
                    case "confirmed" -> confirmed = c.n();
                    default -> throw new IllegalStateException("unknown seat status " + c.status());
                }
            }
        }
        return Optional.of(ShowView.of(id.toString(), show.name(), show.pricePaise(), show.perUserLimit(),
                show.totalSeats(), available, held, confirmed, seats));
    }
}
