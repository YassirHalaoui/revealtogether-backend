package revealtogether.websockets.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Release announcement, broadcast on /topic/votes/{sessionId} at server zero.
 *
 * DELIBERATELY RESULT-FREE. The gender must never ride a sessionId-keyed
 * frame — anyone retaining a sessionId from an old or rotated link would
 * bypass token revocation. Clients react to this by refetching
 * GET /api/public/reveals/{publicToken}, the only guest path that returns
 * the result.
 */
public record RevealEvent(
        String type,
        Instant revealAt,
        String eventId
) {
    public static RevealEvent ready(Instant revealAt) {
        return new RevealEvent("reveal.ready", revealAt, UUID.randomUUID().toString());
    }
}
