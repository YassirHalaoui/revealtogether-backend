package revealtogether.websockets.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import revealtogether.websockets.domain.VoteCount;
import revealtogether.websockets.repository.RedisRepository;

import java.util.Collections;
import java.util.Set;

@Component
public class VoteBroadcastScheduler {

    private static final Logger log = LoggerFactory.getLogger(VoteBroadcastScheduler.class);

    private final RedisRepository redisRepository;
    private final SimpMessagingTemplate messagingTemplate;

    // Cache active sessions — only refresh from Redis every 30s when idle
    private Set<String> cachedActiveSessions = Collections.emptySet();
    private long lastFullCheckTime = 0;
    private static final long FULL_CHECK_INTERVAL_MS = 30_000;

    public VoteBroadcastScheduler(
            RedisRepository redisRepository,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.redisRepository = redisRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedRateString = "${app.broadcast.interval-ms:2000}")
    public void broadcastVotes() {
        long now = System.currentTimeMillis();

        // Only fetch active sessions from Redis every 30s when cache is empty (idle)
        // When there ARE active sessions, refresh every cycle to pick up new ones
        if (!cachedActiveSessions.isEmpty() || now - lastFullCheckTime >= FULL_CHECK_INTERVAL_MS) {
            cachedActiveSessions = redisRepository.getActiveSessions();
            lastFullCheckTime = now;
        }

        if (cachedActiveSessions.isEmpty()) {
            return;
        }

        for (String sessionId : cachedActiveSessions) {
            if (redisRepository.isDirtyAndClear(sessionId)) {
                VoteCount votes = redisRepository.getVotes(sessionId);
                messagingTemplate.convertAndSend("/topic/votes/" + sessionId, votes);
                log.trace("Broadcast votes for session {}: {}", sessionId, votes);
            }
        }
    }
}
