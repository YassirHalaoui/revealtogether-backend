package revealtogether.websockets.dto;

/**
 * Lightweight seat event, broadcast on /topic/seats/{sessionId}.
 *
 * Two frame types (null fields are omitted by the global non_null Jackson config):
 * - "seats":          {"type":"seats","joined":N,"limit":M}      — on every fresh seat claim
 * - "tier_refreshed": {"type":"tier_refreshed","tier":"celebration","seatLimit":150,"joined":N}
 *                                                                 — when refresh-tier lifts the gate
 *
 * Lets waitlisted guests get admitted the second the host upgrades and the
 * host's live counter tick in real time, replacing the frontend's 45s poll.
 */
public record SeatEvent(
        String type,
        long joined,
        Integer limit,
        String tier,
        Integer seatLimit
) {
    public static SeatEvent seats(long joined, Integer limit) {
        return new SeatEvent("seats", joined, limit, null, null);
    }

    public static SeatEvent tierRefreshed(String tier, Integer seatLimit, long joined) {
        return new SeatEvent("tier_refreshed", joined, null, tier, seatLimit);
    }
}
