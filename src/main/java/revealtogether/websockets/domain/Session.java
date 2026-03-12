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
        String fatherName
) {
    public Session withStatus(SessionStatus newStatus) {
        return new Session(sessionId, ownerId, gender, newStatus, revealTime, createdAt, motherName, fatherName);
    }
}
