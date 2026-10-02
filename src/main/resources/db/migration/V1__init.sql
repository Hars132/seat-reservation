-- Money is always integer paise (bigint). Never floats.

CREATE TABLE shows (
    id               uuid PRIMARY KEY,
    name             text        NOT NULL,
    price_paise      bigint      NOT NULL CHECK (price_paise > 0),
    per_user_limit   int         NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id            uuid PRIMARY KEY,
    show_id       uuid        NOT NULL REFERENCES shows(id),
    user_id       text        NOT NULL,
    seats         text[]      NOT NULL,
    amount_paise  bigint      NOT NULL CHECK (amount_paise > 0),
    status        text        NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    cancelled_at  timestamptz
);
CREATE INDEX reservations_user_idx ON reservations (user_id);

-- One row per physical seat. PRIMARY KEY (show_id, label) = a seat exists exactly once.
-- The atomic claim is: UPDATE seats SET status='confirmed', ... WHERE status='available'.
CREATE TABLE seats (
    show_id         uuid NOT NULL REFERENCES shows(id),
    label           text NOT NULL,
    status          text NOT NULL DEFAULT 'available'
                    CHECK (status IN ('available', 'held', 'confirmed')),
    reservation_id  uuid,
    user_id         text,
    PRIMARY KEY (show_id, label),
    -- Safety net: a seat is either free with no owner, or taken with a full owner.
    CONSTRAINT seats_state_consistent CHECK (
        (status = 'available' AND reservation_id IS NULL AND user_id IS NULL)
        OR
        (status IN ('held', 'confirmed') AND reservation_id IS NOT NULL AND user_id IS NOT NULL)
    ),
    -- Deferred so seats can be claimed before the reservation row is inserted in the same txn.
    CONSTRAINT seats_reservation_fk FOREIGN KEY (reservation_id)
        REFERENCES reservations(id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX seats_reservation_idx ON seats (reservation_id);

-- Per-user, per-show counter. Locked with SELECT ... FOR UPDATE to serialize one user's
-- parallel requests so the limit check cannot be raced.
CREATE TABLE user_show_quota (
    show_id  uuid NOT NULL REFERENCES shows(id),
    user_id  text NOT NULL,
    held     int  NOT NULL DEFAULT 0 CHECK (held >= 0),
    PRIMARY KEY (show_id, user_id)
);

-- Exactly-once. PRIMARY KEY (user_id, idem_key) makes a duplicate key impossible; a concurrent
-- duplicate blocks on the unique index until the first transaction commits.
-- 'pending' exists only inside the open transaction. Committed rows are 'confirmed' or 'declined',
-- so a declined key is remembered and a retry with different seats still gets 409.
CREATE TABLE idempotency (
    user_id         text        NOT NULL,
    idem_key        text        NOT NULL,
    request_hash    text        NOT NULL,
    outcome         text        NOT NULL DEFAULT 'pending'
                    CHECK (outcome IN ('pending', 'confirmed', 'declined')),
    reservation_id  uuid REFERENCES reservations(id),
    decline_reason  text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key)
);
