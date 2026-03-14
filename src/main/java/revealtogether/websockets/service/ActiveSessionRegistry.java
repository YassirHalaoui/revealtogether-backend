package revealtogether.websockets.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import revealtogether.websockets.repository.RedisRepository;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry split by status to minimize Redis commands.
 *
 * - waitingSessions: not yet live. RevealScheduler checks every 10s (not 1s).
 * - liveSessions: accepting votes. Both schedulers run at full rate.
 *
 * Cost: WAITING session = ~0.1 cmd/sec vs 3 cmd/sec before.
 * A session waiting 3h now costs 1,080 cmds instead of 32,400.
 */
@Component
public class ActiveSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveSessionRegistry.class);

    private final Set<String> waitingSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> liveSessions = ConcurrentHashMap.newKeySet();
    private final RedisRepository redisRepository;

    public ActiveSessionRegistry(RedisRepository redisRepository) {
        this.redisRepository = redisRepository;
    }

    @PostConstruct
    public void initialize() {
        reconcileWithRedis();
        log.info("ActiveSessionRegistry initialized: {} waiting, {} live",
                waitingSessions.size(), liveSessions.size());
    }

    public void register(String sessionId) {
        waitingSessions.add(sessionId);
        log.debug("Session registered as waiting: {}", sessionId);
    }

    public void markLive(String sessionId) {
        waitingSessions.remove(sessionId);
        liveSessions.add(sessionId);
        log.debug("Session marked live: {}", sessionId);
    }

    public void unregister(String sessionId) {
        waitingSessions.remove(sessionId);
        liveSessions.remove(sessionId);
        log.debug("Session unregistered: {}", sessionId);
    }

    public Set<String> getWaitingSessions() {
        return Collections.unmodifiableSet(waitingSessions);
    }

    public Set<String> getLiveSessions() {
        return Collections.unmodifiableSet(liveSessions);
    }

    public Set<String> getAllSessions() {
        Set<String> all = new HashSet<>();
        all.addAll(waitingSessions);
        all.addAll(liveSessions);
        return all;
    }

    public boolean hasActiveSessions() {
        return !waitingSessions.isEmpty() || !liveSessions.isEmpty();
    }

    public boolean hasLiveSessions() {
        return !liveSessions.isEmpty();
    }

    @Scheduled(fixedRate = 60_000)
    public void reconcileWithRedis() {
        try {
            Set<String> redisSessions = redisRepository.getActiveSessions();
            Set<String> valid = new HashSet<>();

            for (String sessionId : redisSessions) {
                if (redisRepository.sessionExists(sessionId)) {
                    valid.add(sessionId);
                } else {
                    redisRepository.removeActiveSession(sessionId);
                    log.info("Cleaned up phantom session: {}", sessionId);
                }
            }

            Set<String> currentLive = new HashSet<>(liveSessions);
            waitingSessions.clear();
            liveSessions.clear();

            for (String sessionId : valid) {
                if (currentLive.contains(sessionId)) {
                    liveSessions.add(sessionId);
                } else {
                    waitingSessions.add(sessionId);
                }
            }

            log.debug("Reconciled: {} waiting, {} live ({} phantoms removed)",
                    waitingSessions.size(), liveSessions.size(),
                    redisSessions.size() - valid.size());
        } catch (Exception e) {
            log.warn("Redis reconciliation failed, keeping local state", e);
        }
    }
}
