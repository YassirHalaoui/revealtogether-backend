package revealtogether.websockets.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.domain.SessionStatus;
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

    public RevealScheduler(
            SessionService sessionService,
            VoteService voteService,
            ChatService chatService,
            FirebaseService firebaseService,
            SimpMessagingTemplate messagingTemplate,
            ActiveSessionRegistry sessionRegistry
    ) {
        this.sessionService = sessionService;
        this.voteService = voteService;
        this.chatService = chatService;
        this.firebaseService = firebaseService;
        this.messagingTemplate = messagingTemplate;
        this.sessionRegistry = sessionRegistry;
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

    @Scheduled(fixedRate = 10_000)
    public void checkWaitingSessions() {
        if (sessionRegistry.getWaitingSessions().isEmpty()) {
            return;
        }

        Instant now = Instant.now();

        // Check waiting sessions every 10s — activating 5 min before reveal doesn't need 1s precision
        for (String sessionId : new ArrayList<>(sessionRegistry.getWaitingSessions())) {
            sessionService.getSession(sessionId).ifPresent(session -> {
                if (session.status() == SessionStatus.WAITING &&
                        now.isAfter(session.revealTime().minusSeconds(300))) {
                    sessionService.activateSession(sessionId);
                    log.info("Session {} activated", sessionId);
                }
            });
        }
    }

    private void triggerReveal(Session session) {
        String sessionId = session.sessionId();
        log.info("Triggering reveal for session: {}", sessionId);

        // Get final data
        VoteCount finalVotes = voteService.getVotes(sessionId);
        var chatHistory = chatService.getAllMessages(sessionId);

        // Save to Firebase
        firebaseService.saveRevealResults(session, finalVotes, chatHistory);

        // Broadcast reveal event
        RevealEvent revealEvent = RevealEvent.of(session.gender(), finalVotes);
        messagingTemplate.convertAndSend("/topic/votes/" + sessionId, revealEvent);

        // Mark session as ended (this also unregisters from ActiveSessionRegistry)
        sessionService.endSession(sessionId);

        log.info("Reveal completed for session {}: gender={}, votes={}",
                sessionId, session.gender(), finalVotes);
    }
}
