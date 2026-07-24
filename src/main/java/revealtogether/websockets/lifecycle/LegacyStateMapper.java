package revealtogether.websockets.lifecycle;

import java.time.Instant;
import java.util.Map;

/**
 * WP3 — maps the ad-hoc statuses of the 135 existing reveals onto the canonical
 * lifecycle, WITHOUT touching public link fields.
 *
 * The production inventory (2026-07-24) found four shapes:
 *   ended   ×71  → ENDED
 *   waiting ×24  → PUBLISHED, or LOBBY_OPEN inside the 30-minute window
 *   (absent) ×40 → abandoned client-created drafts: auto-generated Firestore
 *                  ids, paymentStatus "pending", zero votes → DRAFT
 *   live         → LOBBY_OPEN
 *
 * DRAFT is chosen over CANCELLED for the status-less docs because it is
 * reversible: an owner can still publish one, and a later retention policy can
 * cancel it. Mapping straight to CANCELLED would destroy that option.
 */
public final class LegacyStateMapper {

    private static final long LOBBY_WINDOW_SECONDS = 1800;

    private LegacyStateMapper() {}

    public record Mapping(RevealState state, String reason) {}

    public static Mapping map(Map<String, Object> doc, Instant now) {
        if (doc == null) return new Mapping(RevealState.DRAFT, "missing document");

        // An explicit canonical state already present wins (idempotent backfill).
        RevealState existing = RevealState.parse(str(doc.get("state")));
        if (existing != null) return new Mapping(existing, "already migrated");

        String legacy = str(doc.get("status"));
        boolean revealed = Boolean.TRUE.equals(doc.get("isRevealed"));
        Instant revealAt = parseInstant(doc.get("revealTime"));

        if ("ended".equalsIgnoreCase(legacy) || revealed) {
            return new Mapping(RevealState.ENDED, "legacy ended/isRevealed");
        }

        if ("live".equalsIgnoreCase(legacy)) {
            return new Mapping(RevealState.LOBBY_OPEN, "legacy live");
        }

        if ("waiting".equalsIgnoreCase(legacy)) {
            // LOBBY_OPEN needs the moment to be genuinely imminent. Without a
            // lower bound every long-past reveal that never fired would map to
            // an open lobby — 19 of the 135 production docs are exactly that.
            boolean imminent = revealAt != null
                    && !revealAt.isBefore(now)
                    && !revealAt.isAfter(now.plusSeconds(LOBBY_WINDOW_SECONDS));
            if (imminent) {
                return new Mapping(RevealState.LOBBY_OPEN, "waiting, inside lobby window");
            }
            // Published but never committed — including reveals whose time
            // passed while still 'waiting'.
            return new Mapping(RevealState.PUBLISHED, "waiting, published but not committed");
        }

        if ("pending".equalsIgnoreCase(legacy)) {
            // Paid + scheduled = a real upcoming reveal; otherwise an unpaid draft.
            boolean paid = "completed".equals(str(doc.get("paymentStatus")));
            if (paid && revealAt != null) {
                return new Mapping(RevealState.PUBLISHED, "pending but paid and scheduled");
            }
            return new Mapping(RevealState.AWAITING_SECRET, "pending, unpaid draft");
        }

        // No status at all: abandoned client-created draft.
        return new Mapping(RevealState.DRAFT, "no legacy status");
    }

    private static String str(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
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
