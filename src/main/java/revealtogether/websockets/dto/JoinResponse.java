package revealtogether.websockets.dto;

/**
 * Response for POST /api/session/{sessionId}/join.
 *
 * Contract (locked with frontend):
 * - status: 'joined' | 'at_capacity' | 'watch_only'
 * - joined: seats consumed so far (host devices and merged devices excluded)
 * - limit:  the MARKETING seat limit (10/150), never the internal grace gate.
 *           null = uncapped (legacy reveal or Grand tier).
 *   joined may legitimately exceed limit by up to the grace buffer; the
 *   frontend renders "limit/limit full" when joined >= limit.
 */
public record JoinResponse(
        String status,
        long joined,
        Integer limit
) {
    public static final String JOINED = "joined";
    public static final String AT_CAPACITY = "at_capacity";
    public static final String WATCH_ONLY = "watch_only";

    public static JoinResponse joined(long joined, Integer limit) {
        return new JoinResponse(JOINED, joined, limit);
    }

    public static JoinResponse atCapacity(long joined, Integer limit) {
        return new JoinResponse(AT_CAPACITY, joined, limit);
    }

    public static JoinResponse watchOnly(long joined, Integer limit) {
        return new JoinResponse(WATCH_ONLY, joined, limit);
    }
}
