-- total_seats is fixed at creation and never updated. GET /shows/{id} reports it next to the live
-- per-status counts, so "available + held + confirmed == total_seats" is a real integrity check,
-- not a tautology.
ALTER TABLE shows ADD COLUMN total_seats int NOT NULL DEFAULT 0;

-- Preserves the order seats were supplied in (A1, A2, ... A10), which label sorting would lose.
-- Seat claims still lock rows in label order; position is for display only.
ALTER TABLE seats ADD COLUMN position int NOT NULL DEFAULT 0;
