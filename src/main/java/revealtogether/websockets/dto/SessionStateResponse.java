package revealtogether.websockets.dto;

import revealtogether.websockets.domain.ChatMessage;
import revealtogether.websockets.domain.SessionStatus;
import revealtogether.websockets.domain.VoteCount;
import revealtogether.websockets.domain.VoteOption;
import revealtogether.websockets.domain.VoteRecord;

import java.time.Instant;
import java.util.List;

public record SessionStateResponse(
        String sessionId,
        SessionStatus status,
        Instant revealTime,
        VoteCount votes,
        List<VoteRecord> recentVotes,
        List<ChatMessage> recentMessages,
        boolean hasVoted,
        VoteOption revealedGender
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
        return new SessionStateResponse(sessionId, status, revealTime, votes, recentVotes, messages, hasVoted, null);
    }

    public static SessionStateResponse ended(
            String sessionId,
            Instant revealTime,
            VoteCount votes,
            List<VoteRecord> recentVotes,
            List<ChatMessage> messages,
            VoteOption gender
    ) {
        return new SessionStateResponse(
                sessionId, SessionStatus.ENDED, revealTime, votes, recentVotes, messages, true, gender
        );
    }
}
