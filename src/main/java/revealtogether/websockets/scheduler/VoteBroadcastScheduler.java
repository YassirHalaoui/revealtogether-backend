package revealtogether.websockets.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import revealtogether.websockets.domain.VoteCount;
import revealtogether.websockets.repository.RedisRepository;

import java.util.Set;

@Component
public class VoteBroadcastScheduler {

    private static final Logger log = LoggerFactory.getLogger(VoteBroadcastScheduler.class);

    private final RedisRepository redisRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public VoteBroadcastScheduler(
            RedisRepository redisRepository,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.redisRepository = redisRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedRateString = "${app.broadcast.interval-ms:200}")
    public void broadcastVotes() {
        Set<String> activeSessions = redisRepository.getActiveSessions();

        for (String sessionId : activeSessions) {
            if (redisRepository.isDirtyAndClear(sessionId)) {
                VoteCount votes = redisRepository.getVotes(sessionId);
                messagingTemplate.convertAndSend("/topic/votes/" + sessionId, votes);
                log.trace("Broadcast votes for session {}: {}", sessionId, votes);
            }
        }
    }
}
