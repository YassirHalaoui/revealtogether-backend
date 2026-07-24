package revealtogether.websockets.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WP4 — the single writer for the enveloped stream on /topic/reveal/{id}.
 *
 * ORDERING: every publish for a given reveal goes through the per-reveal lock
 * below, so frames reach the broker in seq order. Spring's SimpleBroker then
 * preserves per-subscription order, which is what lets a client detect a gap
 * (seq jumped) rather than silently mis-apply reordered events.
 *
 * COEXISTENCE: dual-publish is on by default — new enveloped frames go to
 * /topic/reveal/{id} while the existing /topic/votes, /topic/vote-events,
 * /topic/seats and /topic/chat topics keep working untouched. Nothing breaks
 * until clients choose to migrate.
 *
 * If this service ever runs multi-instance, per-reveal ordering must move to a
 * Redis stream or a partitioned relay; the lock is honest only for one JVM,
 * which is the current Railway deployment (1 replica).
 */
@Service
public class RevealEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RevealEventPublisher.class);
    public static final String TOPIC_PREFIX = "/topic/reveal/";

    private final SimpMessagingTemplate messagingTemplate;
    private final boolean enabled;

    // Fallback sequence for events that aren't lifecycle transitions (votes,
    // joins) and therefore have no Firestore-allocated seq of their own.
    private final Map<String, AtomicLong> localSeq = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public RevealEventPublisher(
            SimpMessagingTemplate messagingTemplate,
            @Value("${app.realtime.envelope-enabled:true}") boolean enabled
    ) {
        this.messagingTemplate = messagingTemplate;
        this.enabled = enabled;
    }

    /** Publishes with a caller-supplied seq (lifecycle transitions from WP3). */
    public void publish(EventEnvelope envelope) {
        if (!enabled) return;
        Object lock = locks.computeIfAbsent(envelope.revealId(), k -> new Object());
        synchronized (lock) {
            // Keep the local counter ahead of authoritative seqs so a later
            // non-lifecycle event can never reuse or precede one.
            localSeq.computeIfAbsent(envelope.revealId(), k -> new AtomicLong(0))
                    .updateAndGet(current -> Math.max(current, envelope.seq()));
            messagingTemplate.convertAndSend(TOPIC_PREFIX + envelope.revealId(), envelope);
            log.debug("Published {} seq={} for {}", envelope.type(), envelope.seq(), envelope.revealId());
        }
    }

    /** Publishes a non-lifecycle event, allocating the next local seq. */
    public void publish(String type, String revealId, long version, Map<String, Object> payload) {
        if (!enabled) return;
        Object lock = locks.computeIfAbsent(revealId, k -> new Object());
        synchronized (lock) {
            long seq = localSeq.computeIfAbsent(revealId, k -> new AtomicLong(0)).incrementAndGet();
            messagingTemplate.convertAndSend(TOPIC_PREFIX + revealId,
                    EventEnvelope.of(type, revealId, seq, version, payload));
            log.debug("Published {} seq={} for {}", type, seq, revealId);
        }
    }

    /** Aligns the local counter with an authoritative seq (e.g. after a snapshot). */
    public void syncSeq(String revealId, long authoritativeSeq) {
        localSeq.computeIfAbsent(revealId, k -> new AtomicLong(0))
                .updateAndGet(current -> Math.max(current, authoritativeSeq));
    }

    public long currentSeq(String revealId) {
        AtomicLong counter = localSeq.get(revealId);
        return counter == null ? 0 : counter.get();
    }

    /** Frees per-reveal state once a reveal is over. */
    public void forget(String revealId) {
        localSeq.remove(revealId);
        locks.remove(revealId);
    }
}
