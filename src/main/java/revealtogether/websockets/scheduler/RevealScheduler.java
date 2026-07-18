package revealtogether.websockets.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.domain.VoteCount;
import revealtogether.websockets.dto.RevealEvent;
import revealtogether.websockets.service.ActiveSessionRegistry;
import revealtogether.websockets.service.ChatService;
import revealtogether.websockets.service.FirebaseService;
import revealtogether.websockets.service.SessionService;
import revealtogether.websockets.service.VoteService;

import java.time.Instant;
import java.util.ArrayList;

@Component
public class RevealScheduler {

    private static final Logger log = LoggerFactory.getLogger(RevealScheduler.class);

    private final SessionService sessionService;
    private final VoteService voteService;
    private final ChatService chatService;
    private final FirebaseService firebaseService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ActiveSessionRegistry sessionRegistry;
    private final revealtogether.websockets.repository.RedisRepository redisRepository;

    public RevealScheduler(
            SessionService sessionService,
            VoteService voteService,
            ChatService chatService,
            FirebaseService firebaseService,
            SimpMessagingTemplate messagingTemplate,
            ActiveSessionRegistry sessionRegistry,
            revealtogether.websockets.repository.RedisRepository redisRepository
    ) {
        this.sessionService = sessionService;
        this.voteService = voteService;
        this.chatService = chatService;
        this.firebaseService = firebaseService;
        this.messagingTemplate = messagingTemplate;
        this.sessionRegistry = sessionRegistry;
        this.redisRepository = redisRepository;
    }

    @Scheduled(fixedRate = 1000)
    public void checkReveals() {
        if (!sessionRegistry.hasActiveSessions()) {
            return;
        }

        Instant now = Instant.now();

        // Check live sessions every tick — need to detect reveal time passing quickly
        for (String sessionId : new ArrayList<>(sessionRegistry.getLiveSessions())) {
            sessionService.getSession(sessionId).ifPresent(session -> {
                if (now.isAfter(session.revealTime())) {
                    triggerReveal(session);
                }
            });
        }
    }

    /**
     * Every 60s: query Firestore for WAITING sessions whose revealTime is within 30 minutes.
     * Load them into Redis and mark live so schedulers can take over.
     * Firestore reads are free — this replaces all Redis polling for waiting sessions.
     * Sessions created days in advance cost ZERO Redis commands until this window opens.
     */
    @Scheduled(fixedRate = 60_000)
    public void loadUpcomingSessions() {
        var upcoming = firebaseService.getUpcomingSessions(1800); // 30-minute window
        for (var session : upcoming) {
            if (!sessionRegistry.getLiveSessions().contains(session.sessionId())) {
                sessionService.loadIntoRedis(session);
                log.info("Session {} pre-loaded into Redis (reveal in <30min)", session.sessionId());
            }
        }
    }

    private void triggerReveal(Session session) {
        String sessionId = session.sessionId();

        // Idempotent transition: scheduled ticks share a pool, so guard
        // against a slow tick overlapping the next one at zero.
        if (!sessionRegistry.getLiveSessions().contains(sessionId)
                || !redisTryMarkFired(sessionId)) {
            return;
        }

        log.info("Triggering reveal for session: {}", sessionId);

        // Get final data
        VoteCount finalVotes = voteService.getVotes(sessionId);
        var chatHistory = chatService.getAllMessages(sessionId);

        // Save results to Firestore FIRST (blocking) so the token-authorized
        // public endpoint already serves the result when clients refetch on
        // the ready frame. Ordering is the race-freedom guarantee.
        firebaseService.saveRevealResults(session, finalVotes, chatHistory);

        // Result-free release frame. The gender never rides a sessionId-keyed
        // topic; clients refetch the public endpoint with their token.
        messagingTemplate.convertAndSend("/topic/votes/" + sessionId, RevealEvent.ready(session.revealTime()));

        // Mark session as ended (this also unregisters from ActiveSessionRegistry)
        sessionService.endSession(sessionId);

        log.info("Reveal completed for session {}: votes={}", sessionId, finalVotes);
    }

    private boolean redisTryMarkFired(String sessionId) {
        try {
            return redisRepository.markRevealFired(sessionId);
        } catch (Exception e) {
            // Redis hiccup at zero: fail open — a duplicate ready frame is
            // harmless (idempotent refetch), a missed reveal is not.
            log.warn("Reveal-fired guard unavailable for {}, proceeding: {}", sessionId, e.getMessage());
            return true;
        }
    }
}
