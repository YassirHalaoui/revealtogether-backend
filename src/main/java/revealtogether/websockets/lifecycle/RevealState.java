package revealtogether.websockets.lifecycle;

/**
 * WP3 — the canonical reveal lifecycle.
 *
 * Blocking CONDITIONS (missing entitlement, payment pending, secret missing,
 * suspended, degraded) are guard-evaluated fields, never states. A reveal is
 * not "in the payment-pending state"; it is READY with an inactive entitlement.
 */
public enum RevealState {
    DRAFT,
    /** Free tier (Quiniela). Voting open, no entitlement, no secret. */
    GUESS_OPEN,
    AWAITING_SECRET,
    READY,
    PUBLISHED,
    LOBBY_OPEN,
    LOCKED,
    /** Irreversible: past this point the reveal WILL happen. */
    REVEAL_COMMITTED,
    REVEALED,
    ENDED,
    ARCHIVED,
    CANCELLED;

    public boolean isTerminal() {
        return this == ARCHIVED || this == CANCELLED;
    }

    /** After commitment, cancellation is impossible — the outcome is going out. */
    public boolean isCommitted() {
        return this == REVEAL_COMMITTED || this == REVEALED || this == ENDED || this == ARCHIVED;
    }

    /** States in which a guest may cast or change a vote. */
    public boolean isVotingOpen() {
        return this == GUESS_OPEN || this == PUBLISHED || this == LOBBY_OPEN;
    }

    public static RevealState parse(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
