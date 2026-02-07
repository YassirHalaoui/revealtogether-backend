package revealtogether.websockets.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import revealtogether.websockets.repository.RedisRepository;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of active session IDs.
 *
 * Sessions are registered when created via REST API and unregistered when ended.
 * A periodic reconciliation with Redis (every 60s) acts as a safety net for
 * server restarts or missed events.
 *
 * This eliminates ALL Redis polling when no sessions are active (zero commands when idle).
 */
@Component
public class ActiveSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveSessionRegistry.class);

    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();
    private final RedisRepository redisRepository;

    public ActiveSessionRegistry(RedisRepository redisRepository) {
        this.redisRepository = redisRepository;
    }

    @PostConstruct
    public void initialize() {
        reconcileWithRedis();
        log.info("ActiveSessionRegistry initialized with {} sessions", activeSessions.size());
    }

    public void register(String sessionId) {
        activeSessions.add(sessionId);
        log.debug("Session registered: {} (total: {})", sessionId, activeSessions.size());
    }

    public void unregister(String sessionId) {
        activeSessions.remove(sessionId);
        log.debug("Session unregistered: {} (total: {})", sessionId, activeSessions.size());
    }

    public Set<String> getActiveSessions() {
        return Collections.unmodifiableSet(activeSessions);
    }

    public boolean hasActiveSessions() {
        return !activeSessions.isEmpty();
    }

    /**
     * Reconcile local state with Redis every 60 seconds.
     * Safety net for server restarts, crashes, or TTL-expired sessions.
     */
    @Scheduled(fixedRate = 60_000)
    public void reconcileWithRedis() {
        try {
            Set<String> redisSessions = redisRepository.getActiveSessions();
            activeSessions.clear();
            activeSessions.addAll(redisSessions);
            log.debug("Reconciled with Redis: {} active sessions", activeSessions.size());
        } catch (Exception e) {
            log.warn("Redis reconciliation failed, keeping local state ({} sessions)", activeSessions.size(), e);
        }
    }
}
