package revealtogether.websockets.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import revealtogether.websockets.dto.HostSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP4 acceptance: per-reveal ordering is guaranteed server-side, snapshots
 * carry the seq a client should resume from, and no unreleased outcome can ride
 * a sessionId-keyed frame.
 */
@DisplayName("WP4 Realtime envelope")
class RealtimeEnvelopeTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** Captures what would have gone to the broker, in arrival order. */
    private static class CapturingTemplate extends SimpMessagingTemplate {
        final List<Object> sent = Collections.synchronizedList(new ArrayList<>());
        final List<String> destinations = Collections.synchronizedList(new ArrayList<>());

        CapturingTemplate() {
            super(new org.springframework.messaging.MessageChannel() {
                @Override
                public boolean send(org.springframework.messaging.Message<?> message, long timeout) {
                    return true;
                }
            });
        }

        @Override
        public void convertAndSend(String destination, Object payload) {
            destinations.add(destination);
            sent.add(payload);
        }
    }

    @Test
    @DisplayName("Frames land on /topic/reveal/{id} with a monotonic per-reveal seq")
    void publishesMonotonicSeqPerReveal() {
        CapturingTemplate template = new CapturingTemplate();
        RevealEventPublisher publisher = new RevealEventPublisher(template, true);

        for (int i = 0; i < 5; i++) {
            publisher.publish(EventEnvelope.VOTE_CAST, "reveal-a", 1L, Map.of("n", i));
        }
        publisher.publish(EventEnvelope.VOTE_CAST, "reveal-b", 1L, Map.of());

        List<Long> seqA = template.sent.stream()
                .map(EventEnvelope.class::cast)
                .filter(e -> e.revealId().equals("reveal-a"))
                .map(EventEnvelope::seq).toList();

        assertThat(seqA).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(template.destinations).contains("/topic/reveal/reveal-a", "/topic/reveal/reveal-b");

        // Sequences are per-reveal, not global.
        EventEnvelope firstB = (EventEnvelope) template.sent.stream()
                .map(EventEnvelope.class::cast)
                .filter(e -> e.revealId().equals("reveal-b")).findFirst().orElseThrow();
        assertThat(firstB.seq()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Concurrent publishes produce no duplicate and no skipped seq")
    void concurrentPublishesAreGapFree() throws Exception {
        CapturingTemplate template = new CapturingTemplate();
        RevealEventPublisher publisher = new RevealEventPublisher(template, true);

        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        publisher.publish(EventEnvelope.VOTE_CAST, "hot", 1L, Map.of());
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        List<Long> seqs = template.sent.stream().map(EventEnvelope.class::cast)
                .map(EventEnvelope::seq).sorted().toList();

        assertThat(seqs).hasSize(threads * perThread);
        // Exactly 1..N with no duplicates and no holes — a client can trust a
        // gap to mean "I missed something", not "the server raced itself".
        for (int i = 0; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isEqualTo(i + 1L);
        }
    }

    @Test
    @DisplayName("The envelope carries everything the client contract needs")
    void envelopeShape() throws Exception {
        EventEnvelope e = EventEnvelope.of(EventEnvelope.VOTE_CAST, "r_1", 142, 37,
                Map.of("choice", "GIRL", "totals", Map.of("BOY", 12, "GIRL", 15)));

        String json = JSON.writeValueAsString(e);
        assertThat(json)
                .contains("\"type\":\"VoteCast\"")
                .contains("\"seq\":142")
                .contains("\"version\":37")
                .contains("\"schema\":1")
                .contains("serverTime");
        assertThat(e.schema()).isEqualTo(EventEnvelope.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("Disabled envelope publishes nothing (kill switch for coexistence)")
    void killSwitch() {
        CapturingTemplate template = new CapturingTemplate();
        RevealEventPublisher disabled = new RevealEventPublisher(template, false);
        disabled.publish(EventEnvelope.VOTE_CAST, "r", 1L, Map.of());
        disabled.publish(EventEnvelope.of(EventEnvelope.LOBBY_OPENED, "r", 1, 1, Map.of()));
        assertThat(template.sent).isEmpty();
    }

    @Test
    @DisplayName("Lifecycle seqs from WP3 keep the local counter ahead — no reuse")
    void authoritativeSeqAdvancesLocalCounter() {
        CapturingTemplate template = new CapturingTemplate();
        RevealEventPublisher publisher = new RevealEventPublisher(template, true);

        // A WP3 transition allocated seq 10 in Firestore.
        publisher.publish(EventEnvelope.of(EventEnvelope.LOBBY_OPENED, "r", 10, 5, Map.of()));
        // A subsequent vote must not reuse or precede it.
        publisher.publish(EventEnvelope.VOTE_CAST, "r", 5L, Map.of());

        List<Long> seqs = template.sent.stream().map(EventEnvelope.class::cast)
                .map(EventEnvelope::seq).toList();
        assertThat(seqs).containsExactly(10L, 11L);
    }

    // ---------- Snapshot: resume point + the secret boundary ----------

    private static Map<String, Object> revealDoc(String state, boolean withGender) {
        Map<String, Object> d = new HashMap<>();
        d.put("state", state);
        d.put("version", 7);
        d.put("seq", 42);
        d.put("motherName", "Ana");
        d.put("fatherName", "Luis");
        d.put("revealTime", "2026-08-01T18:00:00Z");
        d.put("paymentStatus", "completed");
        d.put("ownerId", "owner-1");
        if (withGender) d.put("gender", "boy");
        return d;
    }

    @Test
    @DisplayName("Host snapshot exposes the resume point (snapshotVersion == latest seq)")
    void hostSnapshotCarriesResumePoint() {
        HostSnapshot snap = HostSnapshot.from("r_1", revealDoc("LOBBY_OPEN", true), 18, Instant.now());
        assertThat(snap.snapshotVersion()).isEqualTo(42L);
        assertThat(snap.version()).isEqualTo(7L);
        assertThat(snap.state()).isEqualTo("LOBBY_OPEN");
        assertThat(snap.joined()).isEqualTo(18L);
        assertThat(snap.serverTime()).isNotNull();
    }

    @Test
    @DisplayName("Host snapshot may carry the outcome — the owner chose it — but only this owner-authed path can")
    void hostSnapshotIncludesOutcomeForOwner() {
        HostSnapshot snap = HostSnapshot.from("r_1", revealDoc("LOBBY_OPEN", true), 0, Instant.now());
        assertThat(snap.outcome()).isNotNull();
        assertThat(snap.outcome().gender()).isEqualTo("boy");
        // Pre-release there is no revealedAt to report.
        assertThat(snap.outcome().revealedAt()).isNull();
        assertThat(snap.secretSealed()).isTrue();
    }

    @Test
    @DisplayName("No lifecycle event payload ever carries the outcome")
    void lifecycleFramesAreOutcomeFree() throws Exception {
        for (String type : List.of(EventEnvelope.LOBBY_OPENED, EventEnvelope.VOTING_LOCKED,
                EventEnvelope.REVEAL_COMMITTED, EventEnvelope.OUTCOME_RELEASED,
                EventEnvelope.REVEAL_ENDED)) {
            EventEnvelope e = EventEnvelope.of(type, "r_1", 1, 1,
                    Map.of("fromState", "LOCKED", "toState", "REVEAL_COMMITTED"));
            String json = JSON.writeValueAsString(e).toLowerCase();
            assertThat(json)
                    .as("%s must not leak the outcome", type)
                    .doesNotContain("\"boy\"").doesNotContain("\"girl\"").doesNotContain("gender");
        }
    }

    @Test
    @DisplayName("Vote frames carry totals, never the outcome")
    void voteFramesCarryTotalsOnly() throws Exception {
        EventEnvelope e = EventEnvelope.of(EventEnvelope.VOTE_CAST, "r_1", 3, 2,
                Map.of("participantId", "p_1", "choice", "GIRL",
                        "totals", Map.of("BOY", 12, "GIRL", 15)));
        String json = JSON.writeValueAsString(e);
        // A guest's own guess is theirs to see; the reveal's answer is not here.
        assertThat(json).contains("\"choice\":\"GIRL\"").contains("\"totals\"");
        assertThat(json).doesNotContain("gender").doesNotContain("outcome");
    }
}
