package revealtogether.websockets.dto;

import revealtogether.websockets.domain.ChatMessage;
import revealtogether.websockets.domain.SessionStatus;
import revealtogether.websockets.domain.VoteCount;
import revealtogether.websockets.domain.VoteRecord;

import java.time.Instant;
import java.util.List;

/**
 * Session state for reconnection flows, addressed by sessionId.
 *
 * NEVER carries the gender — sessionId-addressed responses must not expose
 * the result, or a client retaining a sessionId from an old/rotated link
 * would bypass public-token revocation. The result is served exclusively by
 * the token-authorized GET /api/public/reveals/{publicToken}.
 */
public record SessionStateResponse(
        String sessionId,
        SessionStatus status,
        Instant revealTime,
        VoteCount votes,
        List<VoteRecord> recentVotes,
        List<ChatMessage> recentMessages,
        boolean hasVoted
) {
    public static SessionStateResponse live(
            String sessionId,
            SessionStatus status,
            Instant revealTime,
            VoteCount votes,
            List<VoteRecord> recentVotes,
            List<ChatMessage> messages,
            boolean hasVoted
    ) {
        return new SessionStateResponse(sessionId, status, revealTime, votes, recentVotes, messages, hasVoted);
    }

    public static SessionStateResponse ended(
            String sessionId,
            Instant revealTime,
            VoteCount votes,
            List<VoteRecord> recentVotes,
            List<ChatMessage> messages
    ) {
        return new SessionStateResponse(
                sessionId, SessionStatus.ENDED, revealTime, votes, recentVotes, messages, true
        );
    }
}
