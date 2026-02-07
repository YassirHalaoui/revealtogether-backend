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
import java.util.List;

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
        // Zero Redis cost when no active sessions
        if (!sessionRegistry.hasActiveSessions()) {
            return;
        }

        Instant now = Instant.now();

        // Copy to avoid ConcurrentModificationException when unregistering during iteration
        List<String> sessions = new ArrayList<>(sessionRegistry.getActiveSessions());

        for (String sessionId : sessions) {
            sessionService.getSession(sessionId).ifPresent(session -> {
                // Activate waiting sessions 5 minutes before reveal
                if (session.status() == SessionStatus.WAITING &&
                        now.isAfter(session.revealTime().minusSeconds(300))) {
                    sessionService.activateSession(sessionId);
                    log.info("Session {} activated", sessionId);
                }

                // End sessions when reveal time passes
                if (session.status() != SessionStatus.ENDED &&
                        now.isAfter(session.revealTime())) {
                    triggerReveal(session);
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
