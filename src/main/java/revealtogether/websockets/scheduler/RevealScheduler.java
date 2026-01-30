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
import revealtogether.websockets.repository.RedisRepository;
import revealtogether.websockets.service.ChatService;
import revealtogether.websockets.service.FirebaseService;
import revealtogether.websockets.service.SessionService;
import revealtogether.websockets.service.VoteService;

import java.time.Instant;
import java.util.Set;

@Component
public class RevealScheduler {

    private static final Logger log = LoggerFactory.getLogger(RevealScheduler.class);

    private final RedisRepository redisRepository;
    private final SessionService sessionService;
    private final VoteService voteService;
    private final ChatService chatService;
    private final FirebaseService firebaseService;
    private final SimpMessagingTemplate messagingTemplate;

    public RevealScheduler(
            RedisRepository redisRepository,
            SessionService sessionService,
            VoteService voteService,
            ChatService chatService,
            FirebaseService firebaseService,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.redisRepository = redisRepository;
        this.sessionService = sessionService;
        this.voteService = voteService;
        this.chatService = chatService;
        this.firebaseService = firebaseService;
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedRate = 1000) // Check every second
    public void checkReveals() {
        Set<String> activeSessions = redisRepository.getActiveSessions();
        Instant now = Instant.now();

        for (String sessionId : activeSessions) {
            sessionService.getSession(sessionId).ifPresent(session -> {
                // Activate waiting sessions when it's time
                if (session.status() == SessionStatus.WAITING &&
                        now.isAfter(session.revealTime().minusSeconds(300))) {
                    // Activate 5 minutes before reveal
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

        // Mark session as ended
        sessionService.endSession(sessionId);

        log.info("Reveal completed for session {}: gender={}, votes={}",
                sessionId, session.gender(), finalVotes);
    }
}
