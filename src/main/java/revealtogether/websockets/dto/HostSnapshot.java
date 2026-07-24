package revealtogether.websockets.dto;

import revealtogether.websockets.lifecycle.LegacyStateMapper;
import revealtogether.websockets.lifecycle.RevealState;
import revealtogether.websockets.service.FirebaseService;

import java.time.Instant;
import java.util.Map;

/**
 * WP4 — the host control-room snapshot for GET /api/reveals/{id}/snapshot.
 *
 * ROLE BOUNDARY. This endpoint is sessionId-addressed and owner-authenticated:
 *   - the OWNER may see the outcome, because the owner chose it;
 *   - no other role reaches this endpoint at all (401/403 before serialisation).
 * Guests use the token-authorised /api/public/reveals/{token}, which remains
 * the only guest path that can ever return the result. That split is what keeps
 * link rotation and revocation meaningful — anyone holding a stale sessionId
 * still learns nothing.
 *
 * `snapshotVersion` is the seq a client should discard events at or below.
 */
public record HostSnapshot(
        String revealId,
        String state,
        long version,
        long snapshotVersion,
        Instant serverTime,
        Instant revealAt,
        String tier,
        Integer seatLimit,
        long joined,
        boolean secretSealed,
        String paymentStatus,
        Display display,
        Outcome outcome
) {
    public record Display(String motherName, String fatherName, String message, String theme, String locale) {}

    public record Outcome(String gender, Instant revealedAt) {}

    public static HostSnapshot from(String revealId, Map<String, Object> doc, long joined, Instant serverTime) {
        RevealState state = RevealState.parse(str(doc.get("state")));
        if (state == null) state = LegacyStateMapper.map(doc, serverTime).state();

        Integer seatLimit = FirebaseService.toNullableInt(doc.get("seatLimit"));
        if (seatLimit != null && seatLimit <= 0) seatLimit = null;

        String gender = str(doc.get("gender"));
        boolean released = state == RevealState.REVEALED || state == RevealState.ENDED
                || state == RevealState.ARCHIVED;

        return new HostSnapshot(
                revealId,
                state.name(),
                asLong(doc.get("version"), 1L),
                asLong(doc.get("seq"), 0L),
                serverTime,
                parseInstant(doc.get("revealTime")),
                str(doc.get("tier")),
                seatLimit,
                joined,
                gender != null || Boolean.TRUE.equals(doc.get("secretSealed")),
                str(doc.get("paymentStatus")),
                new Display(
                        str(doc.get("motherName")), str(doc.get("fatherName")),
                        str(doc.get("message")), str(doc.get("theme")), str(doc.get("locale"))),
                gender == null ? null : new Outcome(gender,
                        released ? parseInstant(doc.get("endedAt")) : null)
        );
    }

    private static String str(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static long asLong(Object v, long dflt) {
        if (v instanceof Number n) return n.longValue();
        try {
            return v == null ? dflt : Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static Instant parseInstant(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof com.google.cloud.Timestamp ts) return ts.toDate().toInstant();
            return Instant.parse(v.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
