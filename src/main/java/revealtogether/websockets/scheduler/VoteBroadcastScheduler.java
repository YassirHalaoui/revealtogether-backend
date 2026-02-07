package revealtogether.websockets.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import revealtogether.websockets.domain.VoteCount;
import revealtogether.websockets.repository.RedisRepository;
import revealtogether.websockets.service.ActiveSessionRegistry;

@Component
public class VoteBroadcastScheduler {

    private static final Logger log = LoggerFactory.getLogger(VoteBroadcastScheduler.class);

    private final RedisRepository redisRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ActiveSessionRegistry sessionRegistry;

    public VoteBroadcastScheduler(
            RedisRepository redisRepository,
            SimpMessagingTemplate messagingTemplate,
            ActiveSessionRegistry sessionRegistry
    ) {
        this.redisRepository = redisRepository;
        this.messagingTemplate = messagingTemplate;
        this.sessionRegistry = sessionRegistry;
    }

    @Scheduled(fixedRateString = "${app.broadcast.interval-ms:500}")
    public void broadcastVotes() {
        // Zero Redis cost when no active sessions
        if (!sessionRegistry.hasActiveSessions()) {
            return;
        }

        for (String sessionId : sessionRegistry.getActiveSessions()) {
            if (redisRepository.isDirtyAndClear(sessionId)) {
                VoteCount votes = redisRepository.getVotes(sessionId);
                messagingTemplate.convertAndSend("/topic/votes/" + sessionId, votes);
                log.trace("Broadcast votes for session {}: {}", sessionId, votes);
            }
        }
    }
}
