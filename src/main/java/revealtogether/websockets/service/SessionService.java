package revealtogether.websockets.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import revealtogether.websockets.domain.*;
import revealtogether.websockets.dto.SessionCreateRequest;
import revealtogether.websockets.dto.SessionStateResponse;
import revealtogether.websockets.repository.RedisRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final int RECENT_MESSAGES_LIMIT = 50;
    private static final int RECENT_VOTES_LIMIT = 50;

    private final RedisRepository redisRepository;

    public SessionService(RedisRepository redisRepository) {
        this.redisRepository = redisRepository;
    }

    public Session createSession(SessionCreateRequest request) {
        String sessionId = UUID.randomUUID().toString();
        Session session = new Session(
                sessionId,
                request.ownerId(),
                VoteOption.fromValue(request.gender()),
                SessionStatus.WAITING,
                request.revealTime(),
                Instant.now()
        );

        redisRepository.saveSession(session);
        redisRepository.initializeVotes(sessionId);

        log.info("Created session: {} with reveal time: {}", sessionId, request.revealTime());
        return session;
    }

    public Optional<Session> getSession(String sessionId) {
        return redisRepository.getSession(sessionId);
    }

    public boolean sessionExists(String sessionId) {
        return redisRepository.sessionExists(sessionId);
    }

    public void updateStatus(String sessionId, SessionStatus status) {
        redisRepository.updateSessionStatus(sessionId, status);
        log.info("Session {} status updated to: {}", sessionId, status);
    }

    public SessionStateResponse getSessionState(String sessionId, String visitorId) {
        Optional<Session> sessionOpt = getSession(sessionId);
        if (sessionOpt.isEmpty()) {
            return null;
        }

        Session session = sessionOpt.get();
        VoteCount votes = redisRepository.getVotes(sessionId);
        List<VoteRecord> recentVotes = redisRepository.getRecentVotes(sessionId, RECENT_VOTES_LIMIT);
        List<ChatMessage> messages = redisRepository.getRecentMessages(sessionId, RECENT_MESSAGES_LIMIT);
        boolean hasVoted = redisRepository.hasVoted(sessionId, visitorId);

        if (session.status() == SessionStatus.ENDED) {
            return SessionStateResponse.ended(
                    sessionId,
                    session.revealTime(),
                    votes,
                    recentVotes,
                    messages,
                    session.gender()
            );
        }

        return SessionStateResponse.live(
                sessionId,
                session.status(),
                session.revealTime(),
                votes,
                recentVotes,
                messages,
                hasVoted
        );
    }

    public void activateSession(String sessionId) {
        updateStatus(sessionId, SessionStatus.LIVE);
    }

    public void endSession(String sessionId) {
        updateStatus(sessionId, SessionStatus.ENDED);
        redisRepository.removeActiveSession(sessionId);
        redisRepository.setPostRevealTtl(sessionId);
    }
}
