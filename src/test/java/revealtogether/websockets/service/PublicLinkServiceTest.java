package revealtogether.websockets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import revealtogether.websockets.dto.PublicRevealState;
import revealtogether.websockets.dto.RevealEvent;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the opaque-link security core. No containers needed.
 * Maps to handoff acceptance tests 1/3 (no gender pre-reveal), 7 (repeated
 * calls never return the result early), and 10 (non-enumeration by format).
 */
@DisplayName("Opaque public link security")
class PublicLinkServiceTest {

    // Mirrors the app's Jackson config (application.yaml sets
    // default-property-inclusion: non_null) so assertions test the real wire
    // format: null fields are OMITTED, never serialized as null.
    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    // ---------- Token shape ----------

    @Test
    @DisplayName("Tokens: rt_ prefix, 35 chars, url-safe, unique, format-validated")
    void tokenShape() {
        Set<String> seen = new HashSet<>();
        PublicLinkService svc = new PublicLinkService(null, null);
        for (int i = 0; i < 1000; i++) {
            String token = PublicLinkService.generateToken();
            assertThat(token).startsWith("rt_").hasSize(35).matches("^rt_[A-Za-z0-9_-]{32}$");
            assertThat(svc.isValidTokenFormat(token)).isTrue();
            assertThat(seen.add(token)).as("no duplicates in 1000 tokens").isTrue();
        }
        assertThat(svc.isValidTokenFormat(null)).isFalse();
        assertThat(svc.isValidTokenFormat("rt_short")).isFalse();
        assertThat(svc.isValidTokenFormat("xx_" + "a".repeat(32))).isFalse();
        assertThat(svc.isValidTokenFormat("rt_" + "a".repeat(31) + "!")).isFalse();
        // Legacy encoded payloads (base64 JSON, much longer) never pass the format gate
        assertThat(svc.isValidTokenFormat("eyJtIjoiTGF1cmEiLCJmIjoiUm9tYW4ifQ")).isFalse();
    }

    @Test
    @DisplayName("Fingerprint is derived from the hash and never contains the token")
    void fingerprintNeverLeaksToken() {
        String token = PublicLinkService.generateToken();
        String fp = PublicLinkService.fingerprint(PublicLinkService.sha256Hex(token));
        assertThat(fp).hasSize(10).doesNotContain(token.substring(3, 13));
    }

    // ---------- The allowlist boundary (acceptance tests 1, 3, 7) ----------

    private Map<String, Object> docWithSecrets(String status) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("status", status);
        doc.put("gender", "boy");                       // THE SECRET
        doc.put("publicToken", "rt_SHOULD_NEVER_APPEAR_IN_OUTPUT_x");
        doc.put("publicTokenHash", "deadbeef".repeat(8));
        doc.put("ownerId", "owner-uid-secret");
        doc.put("paymentStatus", "completed");
        doc.put("revealTime", "2026-07-18T19:30:00Z");
        doc.put("endedAt", "2026-07-18T19:30:01Z");
        doc.put("motherName", "Ana");
        doc.put("fatherName", "Luis");
        doc.put("message", "See you there");
        doc.put("theme", "classic");
        doc.put("locale", "fr");
        doc.put("seatLimit", 150L);
        return doc;
    }

    @Test
    @DisplayName("Pre-reveal state NEVER contains the gender or any secret field, however many times built")
    void preRevealJsonContainsNoSecrets() throws Exception {
        for (String status : new String[]{"waiting", "live", "pending", null}) {
            Map<String, Object> doc = docWithSecrets(status);
            // Acceptance test 7: repeated calls never leak
            for (int i = 0; i < 50; i++) {
                PublicRevealState state = PublicRevealState.from("sess-1", doc, 18, true, Instant.now());
                String json = JSON.writeValueAsString(state);
                assertThat(state.result()).isNull();
                assertThat(json)
                        .doesNotContain("boy").doesNotContain("girl")
                        .doesNotContain("gender")
                        .doesNotContain("SHOULD_NEVER_APPEAR")
                        .doesNotContain("deadbeef")
                        .doesNotContain("owner-uid-secret")
                        .doesNotContain("paymentStatus");
            }
        }
    }

    @Test
    @DisplayName("Post-reveal state contains the result exactly once, under result.gender")
    void postRevealJsonContainsResult() throws Exception {
        PublicRevealState state = PublicRevealState.from(
                "sess-1", docWithSecrets("ended"), 42, true, Instant.now());
        assertThat(state.status()).isEqualTo("revealed");
        assertThat(state.result()).isNotNull();
        assertThat(state.result().gender()).isEqualTo("boy");

        String json = JSON.writeValueAsString(state);
        assertThat(json).contains("\"gender\":\"boy\"")
                .doesNotContain("SHOULD_NEVER_APPEAR")
                .doesNotContain("owner-uid-secret");
    }

    @Test
    @DisplayName("Display block is allowlisted and participation reflects seats")
    void displayAndParticipation() {
        PublicRevealState state = PublicRevealState.from(
                "sess-1", docWithSecrets("waiting"), 18, true, Instant.now());
        assertThat(state.display().motherName()).isEqualTo("Ana");
        assertThat(state.display().locale()).isEqualTo("fr");
        assertThat(state.participation().seatLimit()).isEqualTo(150);
        assertThat(state.participation().joined()).isEqualTo(18);
        assertThat(state.participation().votingOpen()).isTrue();
        assertThat(state.sessionId()).isEqualTo("sess-1");
    }

    // ---------- Edge cases (rollout review) ----------

    @Test
    @DisplayName("Ancient isRevealed=true docs (no status=ended) release the result")
    void ancientIsRevealedDocsAreRevealed() {
        Map<String, Object> doc = docWithSecrets("waiting");
        doc.put("isRevealed", Boolean.TRUE);
        doc.remove("endedAt");
        doc.put("revealedAt", "2025-03-01T18:00:00Z"); // ancient field name

        PublicRevealState state = PublicRevealState.from("sess-1", doc, 0, true, Instant.now());
        assertThat(state.status()).isEqualTo("revealed");
        assertThat(state.result()).isNotNull();
        assertThat(state.result().gender()).isEqualTo("boy");
        assertThat(state.result().revealedAt()).isEqualTo(Instant.parse("2025-03-01T18:00:00Z"));
    }

    @Test
    @DisplayName("isRevealed=false does NOT release; payload stays gender-clean")
    void isRevealedFalseStaysPendingAndClean() throws Exception {
        Map<String, Object> doc = docWithSecrets("waiting");
        doc.put("isRevealed", Boolean.FALSE);
        PublicRevealState state = PublicRevealState.from("sess-1", doc, 0, true, Instant.now());
        assertThat(state.status()).isEqualTo("pending");
        assertThat(JSON.writeValueAsString(state)).doesNotContain("boy").doesNotContain("gender");
    }

    @Test
    @DisplayName("Unpaid reveals carry paymentPending=true; paid omit the field")
    void paymentPendingFlag() throws Exception {
        Map<String, Object> unpaid = docWithSecrets("waiting");
        unpaid.put("paymentStatus", "pending");
        PublicRevealState state = PublicRevealState.from("sess-1", unpaid, 0, true, Instant.now());
        assertThat(state.paymentPending()).isTrue();
        // still gender-clean
        assertThat(JSON.writeValueAsString(state)).doesNotContain("boy");

        PublicRevealState paid = PublicRevealState.from("sess-1", docWithSecrets("waiting"), 0, true, Instant.now());
        assertThat(paid.paymentPending()).isNull();
        assertThat(JSON.writeValueAsString(paid)).doesNotContain("paymentPending");
    }

    @Test
    @DisplayName("Docs with missing revealTime/names never throw; fields null out")
    void sparseAncientDocNeverThrows() {
        Map<String, Object> sparse = new HashMap<>();
        sparse.put("gender", "girl"); // still must not leak
        PublicRevealState state = PublicRevealState.from("sess-1", sparse, 0, true, Instant.now());
        assertThat(state.status()).isEqualTo("pending");
        assertThat(state.revealAt()).isNull();
        assertThat(state.result()).isNull();
    }

    // ---------- Release frame (the realtime adjustment) ----------

    @Test
    @DisplayName("reveal.ready frame is result-free")
    void readyFrameHasNoResult() throws Exception {
        RevealEvent event = RevealEvent.ready(Instant.parse("2026-07-18T19:30:00Z"));
        String json = JSON.writeValueAsString(event);
        assertThat(event.type()).isEqualTo("reveal.ready");
        assertThat(event.eventId()).isNotBlank();
        assertThat(json).doesNotContain("boy").doesNotContain("girl").doesNotContain("gender");
    }
}
