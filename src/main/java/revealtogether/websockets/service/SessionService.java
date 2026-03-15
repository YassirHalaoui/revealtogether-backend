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
    private final ActiveSessionRegistry sessionRegistry;
    private final FirebaseService firebaseService;

    public SessionService(RedisRepository redisRepository, ActiveSessionRegistry sessionRegistry, FirebaseService firebaseService) {
        this.redisRepository = redisRepository;
        this.sessionRegistry = sessionRegistry;
        this.firebaseService = firebaseService;
    }

    public Session createSession(SessionCreateRequest request) {
        String sessionId = UUID.randomUUID().toString();
        Session session = new Session(
                sessionId,
                request.ownerId(),
                VoteOption.fromValue(request.gender()),
                SessionStatus.WAITING,
                request.revealTime(),
                Instant.now(),
                request.motherName(),
                request.fatherName()
        );

        // Do NOT write to Redis on creation — Firestore is the source of truth for waiting sessions.
        // Redis is loaded lazily by RevealScheduler 30 minutes before reveal time.
        // This eliminates all Redis cost for sessions created days in advance.
        log.info("Created session: {} with reveal time: {}", sessionId, request.revealTime());
        return session;
    }

    /**
     * Loads a session into Redis and marks it live in the registry.
     * Called by RevealScheduler when a session enters the 30-min pre-reveal window.
     */
    public void loadIntoRedis(Session session) {
        redisRepository.saveSession(session);

        // Restore existing votes from Firestore — do NOT zero out if votes already exist
        List<VoteRecord> existingVotes = firebaseService.getVoteRecords(session.sessionId());
        if (existingVotes.isEmpty()) {
            redisRepository.initializeVotes(session.sessionId());
        } else {
            long boy = existingVotes.stream().filter(v -> v.option() == VoteOption.BOY).count();
            long girl = existingVotes.stream().filter(v -> v.option() == VoteOption.GIRL).count();
            redisRepository.restoreVotes(session.sessionId(), boy, girl);
            // Restore voters set so duplicate vote prevention still works
            for (VoteRecord record : existingVotes) {
                redisRepository.restoreVoter(session.sessionId(), record.visitorId());
            }
            log.info("Session {} restored {} votes from Firestore (boy={}, girl={})",
                    session.sessionId(), existingVotes.size(), boy, girl);
        }

        // Write LIVE status to Redis immediately — session is entering the active window
        redisRepository.updateSessionStatus(session.sessionId(), SessionStatus.LIVE);
        sessionRegistry.markLive(session.sessionId());
        log.info("Session {} loaded into Redis as LIVE (30-min window)", session.sessionId());
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
        boolean redisExpired = sessionOpt.isEmpty();
        if (redisExpired) {
            // Redis expired — try to reconstruct from Firestore
            sessionOpt = firebaseService.getSessionFromFirestore(sessionId);
            if (sessionOpt.isEmpty()) {
                return null;
            }
        }

        Session session = sessionOpt.get();
        VoteCount votes = redisRepository.getVotes(sessionId);
        List<VoteRecord> recentVotes = redisRepository.getRecentVotes(sessionId, RECENT_VOTES_LIMIT);
        if (redisExpired) {
            // Redis gone — load everything from Firestore
            recentVotes = firebaseService.getVoteRecords(sessionId);
            if (!recentVotes.isEmpty()) {
                long boy = recentVotes.stream().filter(v -> v.option() == VoteOption.BOY).count();
                long girl = recentVotes.stream().filter(v -> v.option() == VoteOption.GIRL).count();
                votes = new VoteCount(boy, girl);
            }
        }
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
        sessionRegistry.markLive(sessionId);
    }

    public void endSession(String sessionId) {
        redisRepository.deleteSession(sessionId);
        sessionRegistry.unregister(sessionId);
    }

    /**
     * Deletes a session entirely. Safe regardless of current status:
     * - Cleans up Redis keys if they exist (no error if already expired)
     * - Unregisters from ActiveSessionRegistry
     * - Firestore deletion is handled by the caller (RevealController)
     *   so it can also broadcast "deleted" before returning the response.
     *
     * @return the session's status before deletion, or null if Redis had already expired
     */
    public SessionStatus deleteSession(String sessionId) {
        Optional<Session> sessionOpt = getSession(sessionId);
        SessionStatus statusBeforeDelete = sessionOpt.map(Session::status).orElse(null);

        // Clean up Redis — all calls are no-ops if keys don't exist
        redisRepository.deleteSession(sessionId);
        sessionRegistry.unregister(sessionId);

        return statusBeforeDelete;
    }
}
