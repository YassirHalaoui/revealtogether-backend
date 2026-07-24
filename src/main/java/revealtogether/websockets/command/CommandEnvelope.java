package revealtogether.websockets.command;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

/**
 * WP2 — the per-request command envelope.
 *
 * Every mutation carries this. All fields are optional on the wire so existing
 * web/Flutter clients keep working unchanged; they become meaningful as clients
 * ship support. Absent trace id is generated so every request is traceable.
 */
public record CommandEnvelope(
        String traceId,
        String idempotencyKey,
        Long expectedVersion,
        String clientPlatform,
        String clientVersion,
        String acceptLanguage,
        String actorKey
) {
    public static final String H_TRACE_ID = "X-Trace-Id";
    public static final String H_IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String H_EXPECTED_VERSION = "X-Expected-Version";
    public static final String H_CLIENT_PLATFORM = "X-Client-Platform";
    public static final String H_CLIENT_VERSION = "X-Client-Version";
    public static final String H_PARTICIPANT_TOKEN = "X-Participant-Token";
    public static final String H_REPLAYED = "Idempotency-Replayed";

    public static CommandEnvelope from(HttpServletRequest request) {
        String traceId = trimToNull(request.getHeader(H_TRACE_ID));
        if (traceId == null) {
            traceId = "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        Long expectedVersion = null;
        String rawVersion = trimToNull(request.getHeader(H_EXPECTED_VERSION));
        if (rawVersion != null) {
            try {
                expectedVersion = Long.parseLong(rawVersion);
            } catch (NumberFormatException ignored) {
                // Malformed header behaves as absent: the guard simply doesn't run.
            }
        }

        return new CommandEnvelope(
                traceId,
                trimToNull(request.getHeader(H_IDEMPOTENCY_KEY)),
                expectedVersion,
                trimToNull(request.getHeader(H_CLIENT_PLATFORM)),
                trimToNull(request.getHeader(H_CLIENT_VERSION)),
                trimToNull(request.getHeader("Accept-Language")),
                actorKey(request)
        );
    }

    /**
     * Stable identity for idempotency scoping. Derived from the caller's
     * credential so the same key replayed by a DIFFERENT caller is a different
     * record. Hashed, never the raw credential — this value reaches Redis.
     */
    private static String actorKey(HttpServletRequest request) {
        String auth = trimToNull(request.getHeader("Authorization"));
        if (auth != null) return "a:" + sha256Short(auth);
        String participant = trimToNull(request.getHeader(H_PARTICIPANT_TOKEN));
        if (participant != null) return "p:" + sha256Short(participant);
        return "anon";
    }

    static String sha256Short(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
