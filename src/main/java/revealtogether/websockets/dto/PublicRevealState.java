package revealtogether.websockets.dto;

import revealtogether.websockets.service.FirebaseService;

import java.time.Instant;
import java.util.Map;

/**
 * Guest-safe reveal state for GET /api/public/reveals/{publicToken}.
 *
 * THE PRIVACY BOUNDARY. This record is built EXCLUSIVELY by allowlisting —
 * every field is explicitly copied; the raw Firestore document is never
 * serialized. `result` is non-null ONLY when the server has marked the
 * reveal ended. The gender must never appear anywhere else in this payload,
 * and this endpoint (token-addressed) is the ONLY guest path allowed to
 * return it — sessionId-addressed endpoints and frames never do.
 */
public record PublicRevealState(
        String status,
        Instant serverNow,
        Instant revealAt,
        String sessionId,
        Boolean paymentPending,
        Display display,
        Participation participation,
        Result result
) {
    public record Display(String motherName, String fatherName, String message, String theme, String locale) {}

    public record Participation(Integer seatLimit, long joined, boolean canJoin, boolean votingOpen) {}

    public record Result(String gender, Instant revealedAt) {}

    /**
     * Pure builder over the Firestore doc map — unit-testable without infra.
     *
     * @param doc     raw reveals/{id} document data
     * @param joined  current seat count (0 for legacy/untracked)
     * @param gateOpen whether a new device could still claim a seat
     */
    public static PublicRevealState from(String sessionId, Map<String, Object> doc,
                                         long joined, boolean gateOpen, Instant serverNow) {
        String docStatus = str(doc.get("status"));
        // Ancient client-created reveals were marked with isRevealed=true
        // instead of status="ended" — without this they'd stay "pending"
        // forever after a legacy-resolve. Both signals were set at reveal
        // time, so releasing the result on either preserves server authority.
        boolean ended = "ended".equals(docStatus)
                || Boolean.TRUE.equals(doc.get("isRevealed"));
        String status = ended ? "revealed" : "live".equals(docStatus) ? "live" : "pending";

        // Post rules-lockdown the frontend can no longer read paymentStatus
        // client-side; this flag is its only gate signal. Omitted when paid.
        Boolean paymentPending = "pending".equals(str(doc.get("paymentStatus"))) ? Boolean.TRUE : null;

        Instant revealAt = parseInstant(doc.get("revealTime"));

        Display display = new Display(
                str(doc.get("motherName")),
                str(doc.get("fatherName")),
                str(doc.get("message")),
                str(doc.get("theme")),
                str(doc.get("locale"))
        );

        Integer seatLimit = FirebaseService.toNullableInt(doc.get("seatLimit"));
        if (seatLimit != null && seatLimit <= 0) {
            seatLimit = null; // stored zero = uncapped, never zero capacity
        }
        boolean votingOpen = !ended;
        Participation participation = new Participation(
                seatLimit, joined, votingOpen && gateOpen, votingOpen);

        // The ONLY place guest-visible gender is populated: server marked it ended.
        Result result = null;
        if (ended) {
            String gender = str(doc.get("gender"));
            if (gender != null) {
                Instant revealedAt = parseInstant(doc.get("endedAt"));
                if (revealedAt == null) {
                    revealedAt = parseInstant(doc.get("revealedAt")); // ancient docs
                }
                result = new Result(gender, revealedAt);
            }
        }

        return new PublicRevealState(status, serverNow, revealAt, sessionId,
                paymentPending, display, participation, result);
    }

    private static String str(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static Instant parseInstant(Object value) {
        if (value == null) return null;
        try {
            if (value instanceof com.google.cloud.Timestamp ts) {
                return ts.toDate().toInstant();
            }
            return Instant.parse(value.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
