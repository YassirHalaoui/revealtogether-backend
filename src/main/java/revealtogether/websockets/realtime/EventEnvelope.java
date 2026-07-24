package revealtogether.websockets.realtime;

import java.time.Instant;
import java.util.Map;

/**
 * WP4 — the wire format for every frame on /topic/reveal/{revealId}.
 *
 * The client contract this enables: subscribe → fetch snapshot → discard events
 * with seq <= snapshotVersion → apply in order → on a gap, pause and refetch the
 * snapshot. That only works if `seq` is per-reveal monotonic, which WP3
 * guarantees by allocating it inside the same transaction as the state change.
 *
 * The payload NEVER carries an unreleased outcome. OutcomeReleased itself is a
 * signal to refetch the token-authorised endpoint, not a carrier of the result.
 */
public record EventEnvelope(
        String type,
        String revealId,
        long seq,
        long version,
        Instant serverTime,
        int schema,
        Map<String, Object> payload
) {
    public static final int SCHEMA_VERSION = 1;

    public static EventEnvelope of(String type, String revealId, long seq, long version,
                                   Map<String, Object> payload) {
        return new EventEnvelope(type, revealId, seq, version, Instant.now(), SCHEMA_VERSION,
                payload == null ? Map.of() : payload);
    }

    /** Lifecycle */
    public static final String REVEAL_PUBLISHED = "RevealPublished";
    public static final String LOBBY_OPENED = "LobbyOpened";
    public static final String VOTING_LOCKED = "VotingLocked";
    public static final String REVEAL_COMMITTED = "RevealCommitted";
    public static final String OUTCOME_RELEASED = "OutcomeReleased";
    public static final String REVEAL_ENDED = "RevealEnded";
    public static final String REVEAL_CANCELLED = "RevealCancelled";
    public static final String SCHEDULE_CHANGED = "ScheduleChanged";

    /** Participation */
    public static final String PARTICIPANT_JOINED = "ParticipantJoined";
    public static final String CAPACITY_CHANGED = "CapacityChanged";
    public static final String VOTE_CAST = "VoteCast";
    public static final String VOTE_TOTALS_UPDATED = "VoteTotalsUpdated";

    /** Social */
    public static final String WISH_POSTED = "WishPosted";
    public static final String MESSAGE_POSTED = "MessagePosted";

    /** Operational */
    public static final String ENTITLEMENT_CHANGED = "EntitlementChanged";
}
