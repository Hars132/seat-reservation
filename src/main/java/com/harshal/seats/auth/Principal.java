package com.harshal.seats.auth;

import com.harshal.seats.web.ApiException;

/** The authenticated caller. Built ONLY from a verified token, never from request body or params. */
public record Principal(String userId, Role role) {

    public void requireAdmin() {
        if (role != Role.ADMIN) {
            throw new ApiException(403, "forbidden", "admin role required");
        }
    }
}
