package revealtogether.websockets.dto;

import java.time.Instant;

/**
 * Individual vote event broadcast to all subscribers.
 * Used for real-time vote animations (floating votes with names).
 */
public record VoteEvent(
        String visitorId,
        String name,
        String option,
        Instant timestamp
) {
    public static VoteEvent create(String visitorId, String name, String option) {
        return new VoteEvent(visitorId, name, option, Instant.now());
    }
}
