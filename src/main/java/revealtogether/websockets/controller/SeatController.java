package revealtogether.websockets.controller;

import com.google.firebase.auth.FirebaseAuth;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import revealtogether.websockets.dto.JoinRequest;
import revealtogether.websockets.dto.JoinResponse;
import revealtogether.websockets.dto.SeatEvent;
import revealtogether.websockets.repository.RedisRepository;
import revealtogether.websockets.service.FirebaseService;
import revealtogether.websockets.service.SeatService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiered-pricing endpoints (contract locked with frontend):
 *
 * POST /api/session/{sessionId}/join            — public; guest seat claim at page mount
 * GET  /api/session/{sessionId}/seat-stats      — host only (Firebase ID token)
 * POST /api/admin/sessions/{sessionId}/refresh-tier — internal (shared secret);
 *      called by the Vercel Stripe webhook after it writes tier/seatLimit to Firestore.
 */
@RestController
@RequestMapping("/api")
public class SeatController {

    private static final Logger log = LoggerFactory.getLogger(SeatController.class);

    private final SeatService seatService;
    private final FirebaseService firebaseService;
    private final RedisRepository redisRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final String internalSecret;

    public SeatController(
            SeatService seatService,
            FirebaseService firebaseService,
            RedisRepository redisRepository,
            SimpMessagingTemplate messagingTemplate,
            @Value("${app.internal-secret:}") String internalSecret
    ) {
        this.seatService = seatService;
        this.firebaseService = firebaseService;
        this.redisRepository = redisRepository;
        this.messagingTemplate = messagingTemplate;
        // Trim defends against trailing whitespace/newlines picked up when the
        // env var was pasted into Railway/Vercel dashboards.
        this.internalSecret = internalSecret == null ? "" : internalSecret.trim();
        // Length only — never the value. Lets ops confirm the env var landed.
        log.info("Internal API secret configured: {} (length {})",
                !this.internalSecret.isBlank(), this.internalSecret.length());
    }

    @PostMapping("/session/{sessionId}/join")
    public ResponseEntity<JoinResponse> join(
            @PathVariable String sessionId,
            @Valid @RequestBody JoinRequest request
    ) {
        JoinResponse response = seatService.join(
                sessionId, request.visitorId(), request.email(), request.idToken());
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/session/{sessionId}/seat-stats")
    public ResponseEntity<?> seatStats(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        String uid = verifyIdToken(authHeader);
        if (uid == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Missing or invalid token"));
        }

        Map<String, Object> reveal = firebaseService.getRevealData(sessionId);
        if (reveal == null) {
            return ResponseEntity.notFound().build();
        }
        String ownerId = (String) reveal.get("ownerId");
        if (ownerId == null || !ownerId.equals(uid)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        JoinResponse stats = seatService.stats(sessionId);
        if (stats == null) {
            return ResponseEntity.notFound().build();
        }
        // Uncapped = limit OMITTED (locked wire contract). Map.of() rejects
        // nulls and the old null->0 coercion here made legacy reveals render
        // as "0/0 seats" with a bogus upgrade offer — never coerce to zero.
        Map<String, Object> body = new HashMap<>();
        body.put("joined", stats.joined());
        if (stats.limit() != null) {
            body.put("limit", stats.limit());
        }
        return ResponseEntity.ok(body);
    }

    /**
     * WP4 — host control-room snapshot. Owner-only: guests use the
     * token-authorised /api/public/reveals/{token}, which stays the sole guest
     * path to the outcome so rotation and revocation keep their meaning.
     */
    @GetMapping("/reveals/{sessionId}/snapshot")
    public ResponseEntity<?> snapshot(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        String uid = verifyIdToken(authHeader);
        if (uid == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "UNAUTHORIZED"));
        }
        Map<String, Object> doc = firebaseService.getRevealData(sessionId);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }
        if (!uid.equals(doc.get("ownerId")) && !uid.equals(doc.get("createdBy"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        JoinResponse stats = seatService.stats(sessionId);
        long joined = stats != null ? stats.joined() : 0;
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore().cachePrivate())
                .body(revealtogether.websockets.dto.HostSnapshot.from(
                        sessionId, doc, joined, java.time.Instant.now()));
    }

    @PostMapping("/admin/sessions/{sessionId}/refresh-tier")
    public ResponseEntity<?> refreshTier(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        if (!internalSecretMatches(authHeader)) {
            log.warn("refresh-tier rejected: bad or missing internal secret for session {}", sessionId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, Object> reveal = firebaseService.getRevealData(sessionId);
        if (reveal == null) {
            return ResponseEntity.notFound().build();
        }

        String tier = (String) reveal.get("tier");
        Integer seatLimit = revealtogether.websockets.service.FirebaseService.toNullableInt(reveal.get("seatLimit"));
        if (seatLimit != null && seatLimit <= 0) {
            seatLimit = null; // stored zero = uncapped, never zero capacity
        }

        redisRepository.updateSessionTier(sessionId, tier, seatLimit);

        JoinResponse stats = seatService.stats(sessionId);
        long joined = stats != null ? stats.joined() : 0;

        // Instant admission: waitlisted guests subscribed to /topic/seats re-check
        // the moment the gate lifts instead of waiting for their next poll.
        messagingTemplate.convertAndSend("/topic/seats/" + sessionId,
                SeatEvent.tierRefreshed(tier, seatLimit, joined));

        log.info("Tier refreshed: session={}, tier={}, seatLimit={}, joined={}",
                sessionId, tier, seatLimit, joined);

        Map<String, Object> body = new HashMap<>();
        body.put("tier", tier);
        body.put("seatLimit", seatLimit);
        body.put("joined", joined);
        return ResponseEntity.ok(body);
    }

    private String verifyIdToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            return FirebaseAuth.getInstance().verifyIdToken(authHeader.substring(7)).getUid();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean internalSecretMatches(String authHeader) {
        if (internalSecret.isBlank()) {
            // Secret not configured: reject everything rather than fail open on an admin path.
            log.warn("refresh-tier auth: INTERNAL_API_SECRET not configured on this deployment");
            return false;
        }
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("refresh-tier auth: Authorization header {} ",
                    authHeader == null ? "missing" : "present but not Bearer");
            return false;
        }
        byte[] provided = authHeader.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        byte[] expected = internalSecret.getBytes(StandardCharsets.UTF_8);
        boolean match = MessageDigest.isEqual(provided, expected);
        if (!match) {
            // Lengths are safe to log and instantly reveal whitespace/truncation issues.
            log.warn("refresh-tier auth: secret mismatch (provided length {}, expected length {})",
                    provided.length, expected.length);
        }
        return match;
    }
}
