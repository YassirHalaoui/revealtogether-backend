package revealtogether.websockets.domain;

import java.time.Instant;

public record Session(
        String sessionId,
        String ownerId,
        VoteOption gender,
        SessionStatus status,
        Instant revealTime,
        Instant createdAt,
        String motherName,
        String fatherName,
        String tier,
        Integer seatLimit
) {
    /** Legacy 8-arg constructor: pre-tier sessions (tier/seatLimit = null = uncapped). */
    public Session(String sessionId, String ownerId, VoteOption gender, SessionStatus status,
                   Instant revealTime, Instant createdAt, String motherName, String fatherName) {
        this(sessionId, ownerId, gender, status, revealTime, createdAt, motherName, fatherName, null, null);
    }

    public Session withStatus(SessionStatus newStatus) {
        return new Session(sessionId, ownerId, gender, newStatus, revealTime, createdAt,
                motherName, fatherName, tier, seatLimit);
    }

    /** Legacy reveals (created before tiered pricing) have neither field — never gated. */
    public boolean isTiered() {
        return tier != null;
    }

    /** A capped session has an explicit seatLimit. Grand tier is tiered but uncapped (null limit). */
    public boolean isCapped() {
        return seatLimit != null;
    }
}
