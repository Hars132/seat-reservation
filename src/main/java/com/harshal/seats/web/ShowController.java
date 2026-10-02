package com.harshal.seats.web;

import com.harshal.seats.auth.AuthFilter;
import com.harshal.seats.auth.Principal;
import com.harshal.seats.service.ShowService;
import com.harshal.seats.service.ShowView;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
public class ShowController {

    public record CreateShowRequest(String name, List<String> seats, Long pricePaise, Integer perUserLimit) {}

    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9_.-]{1,32}");
    private static final int MAX_SEATS = 100_000;
    private static final long MAX_PRICE_PAISE = 1_000_000_000_000L;
    static final int DEFAULT_PER_USER_LIMIT = 4;

    private final ShowService shows;

    public ShowController(ShowService shows) {
        this.shows = shows;
    }

    /** Admin only. Duplicate seat labels are rejected (400), not silently merged. */
    @PostMapping
    public ResponseEntity<ShowView> create(@RequestAttribute(AuthFilter.ATTR) Principal principal,
                                           @RequestBody CreateShowRequest req) {
        principal.requireAdmin();

        if (req.name() == null || req.name().isBlank() || req.name().length() > 200) {
            throw bad("name is required (max 200 characters)");
        }
        List<String> seats = req.seats();
        if (seats == null || seats.isEmpty()) {
            throw bad("seats must be a non-empty list");
        }
        if (seats.size() > MAX_SEATS) {
            throw bad("at most " + MAX_SEATS + " seats per show");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < seats.size(); i++) {
            String s = seats.get(i);
            if (s == null || !LABEL.matcher(s).matches()) {
                throw bad("invalid seat label at index " + i + " (allowed: [A-Za-z0-9_.-], 1-32 chars)");
            }
            if (!seen.add(s)) {
                throw bad("duplicate seat label at index " + i);
            }
        }
        if (req.pricePaise() == null || req.pricePaise() <= 0 || req.pricePaise() > MAX_PRICE_PAISE) {
            throw bad("price_paise must be a positive integer (paise)");
        }
        int limit = req.perUserLimit() == null ? DEFAULT_PER_USER_LIMIT : req.perUserLimit();
        if (limit < 1 || limit > 100) {
            throw bad("per_user_limit must be between 1 and 100");
        }

        ShowView created = shows.create(req.name().trim(), seats, req.pricePaise(), limit);
        return ResponseEntity.created(URI.create("/shows/" + created.id())).body(created);
    }

    /** Any authenticated user. include_seats=false returns counts only (cheaper to poll). */
    @GetMapping("/{id}")
    public ShowView get(@PathVariable String id,
                        @RequestParam(name = "include_seats", defaultValue = "true") boolean includeSeats) {
        UUID uuid = parseId(id);
        return shows.find(uuid, includeSeats)
                .orElseThrow(() -> new ApiException(404, "show_not_found", "show not found"));
    }

    static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(404, "show_not_found", "show not found");
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(400, "invalid_request", message);
    }
}
