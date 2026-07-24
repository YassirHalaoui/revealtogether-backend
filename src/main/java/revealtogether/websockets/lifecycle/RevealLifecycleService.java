package revealtogether.websockets.lifecycle;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * WP3 — executes lifecycle transitions.
 *
 * Every accepted transition runs in ONE Firestore transaction that:
 *   reads current state + version → checks the expected version (optimistic
 *   concurrency) → evaluates guards → writes the new state → increments
 *   version → allocates the next seq → appends the domain event.
 * All of it, or none of it.
 *
 * Coexistence: the legacy `status` field is left untouched, so the existing
 * scheduler and both clients keep working while `state` shadows it.
 */
@Service
public class RevealLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(RevealLifecycleService.class);
    private static final String REVEALS = "reveals";
    private static final String EVENTS = "events";

    private final Firestore firestore;

    public RevealLifecycleService(@Nullable Firestore firestore) {
        this.firestore = firestore;
    }

    public sealed interface Outcome permits Applied, Refused {}

    public record Applied(RevealState from, RevealState to, long version, long seq) implements Outcome {}

    public record Refused(String code, String message, @Nullable Long currentVersion) implements Outcome {}

    /**
     * @param expectedVersion from X-Expected-Version; null skips the check.
     */
    public Outcome apply(String revealId, RevealCommand command,
                         @Nullable Long expectedVersion, @Nullable String actor) {
        if (firestore == null) {
            return new Refused("reveal_unavailable", "Firestore not configured", null);
        }

        try {
            return firestore.runTransaction(tx -> {
                var ref = firestore.collection(REVEALS).document(revealId);
                DocumentSnapshot snap = tx.get(ref).get();
                if (!snap.exists()) {
                    return new Refused("reveal_not_found", "No such reveal", null);
                }

                Map<String, Object> doc = snap.getData();
                long version = asLong(doc.get("version"), 1L);

                if (expectedVersion != null && expectedVersion != version) {
                    return new Refused("VERSION_CONFLICT",
                            "Expected version %d but current is %d".formatted(expectedVersion, version),
                            version);
                }

                // Legacy docs are mapped on the fly, so a transition works even
                // before the backfill has reached this reveal.
                RevealState from = RevealState.parse(str(doc.get("state")));
                if (from == null) {
                    from = LegacyStateMapper.map(doc, Instant.now()).state();
                }

                var result = RevealStateMachine.evaluate(from, command, GuardContext.fromRevealDoc(doc));
                if (result instanceof RevealStateMachine.Rejected rejected) {
                    return new Refused(rejected.code(), rejected.message(), version);
                }

                RevealState to = ((RevealStateMachine.Allowed) result).to();
                long newVersion = version + 1;
                long seq = asLong(doc.get("seq"), 0L) + 1;

                Map<String, Object> update = new HashMap<>();
                update.put("state", to.name());
                update.put("version", newVersion);
                update.put("seq", seq);
                update.put("stateUpdatedAt", Instant.now().toString());
                tx.set(ref, update, SetOptions.merge());

                // The domain event is part of the same transaction: no state
                // change can exist without its event, and vice versa.
                Map<String, Object> event = new HashMap<>();
                event.put("type", eventTypeFor(to));
                event.put("revealId", revealId);
                event.put("seq", seq);
                event.put("version", newVersion);
                event.put("serverTime", Instant.now().toString());
                event.put("schema", 1);
                event.put("command", command.name());
                event.put("fromState", from.name());
                event.put("toState", to.name());
                if (actor != null) event.put("actor", actor);
                tx.set(ref.collection(EVENTS).document(String.valueOf(seq)), event);

                log.info("Lifecycle {}: {} -> {} (v{} seq{})", revealId, from, to, newVersion, seq);
                return new Applied(from, to, newVersion, seq);
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Refused("reveal_unavailable", "Interrupted", null);
        } catch (ExecutionException e) {
            log.error("Lifecycle transition failed for {}", revealId, e);
            return new Refused("reveal_unavailable", "Transition failed", null);
        }
    }

    /** Lifecycle event names per the WP4 envelope. */
    static String eventTypeFor(RevealState to) {
        return switch (to) {
            case GUESS_OPEN -> "GuessPartyPublished";
            case PUBLISHED -> "RevealPublished";
            case LOBBY_OPEN -> "LobbyOpened";
            case LOCKED -> "VotingLocked";
            case REVEAL_COMMITTED -> "RevealCommitted";
            case REVEALED -> "OutcomeReleased";
            case ENDED -> "RevealEnded";
            case CANCELLED -> "RevealCancelled";
            case ARCHIVED -> "RevealArchived";
            default -> "StateChanged";
        };
    }

    private static long asLong(Object v, long dflt) {
        if (v instanceof Number n) return n.longValue();
        try {
            return v == null ? dflt : Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String str(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }
}
