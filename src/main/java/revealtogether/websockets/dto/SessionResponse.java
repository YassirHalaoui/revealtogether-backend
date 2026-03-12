package revealtogether.websockets.dto;

import revealtogether.websockets.domain.Session;
import revealtogether.websockets.domain.SessionStatus;
import revealtogether.websockets.domain.VoteOption;

import java.time.Instant;

public record SessionResponse(
        String sessionId,
        String firestoreId,
        SessionStatus status,
        Instant revealTime,
        Instant createdAt,
        String shareableLink,
        VoteOption gender
) {
    public static SessionResponse from(Session session, String baseUrl) {
        return new SessionResponse(
                session.sessionId(),
                session.sessionId(),   // firestoreId == sessionId (backend owns the doc)
                session.status(),
                session.revealTime(),
                session.createdAt(),
                baseUrl + "/r/" + session.sessionId(),
                session.status() == SessionStatus.ENDED ? session.gender() : null
        );
    }
}
