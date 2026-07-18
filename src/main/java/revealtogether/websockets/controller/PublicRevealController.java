package revealtogether.websockets.controller;

import com.google.firebase.auth.FirebaseAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import revealtogether.websockets.dto.PublicLinkResponse;
import revealtogether.websockets.dto.PublicRevealState;
import revealtogether.websockets.repository.RedisRepository;
import revealtogether.websockets.service.FirebaseService;
import revealtogether.websockets.service.PublicLinkService;
import revealtogether.websockets.service.SeatService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Opaque public reveal links (opaqueRevealLinksV1).
 *
 * Error contract (non-enumerating, exact codes locked with frontend):
 *   400 {"error":"invalid_token"}     — malformed token
 *   404 {"error":"reveal_not_found"}  — unknown / revoked / rotated / deleted
 *   429 {"error":"rate_limited"}      — lookup rate limit
 *   500 {"error":"reveal_unavailable"}— generic; never internals
 */
@RestController
@RequestMapping("/api")
public class PublicRevealController {

    private static final Logger log = LoggerFactory.getLogger(PublicRevealController.class);
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);
    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final PublicLinkService publicLinkService;
    private final FirebaseService firebaseService;
    private final SeatService seatService;
    private final RedisRepository redisRepository;
    private final String internalSecret;
    private final int lookupsPerIpPerMinute;

    public PublicRevealController(
            PublicLinkService publicLinkService,
            FirebaseService firebaseService,
            SeatService seatService,
            RedisRepository redisRepository,
            @Value("${app.internal-secret:}") String internalSecret,
            @Value("${app.public-links.lookups-per-ip-per-minute:120}") int lookupsPerIpPerMinute
    ) {
        this.publicLinkService = publicLinkService;
        this.firebaseService = firebaseService;
        this.seatService = seatService;
        this.redisRepository = redisRepository;
        this.internalSecret = internalSecret == null ? "" : internalSecret.trim();
        this.lookupsPerIpPerMinute = lookupsPerIpPerMinute;
    }

    // ---------- Provisioning (owner or internal service) ----------

    @PostMapping("/reveals/{revealId}/public-link")
    public ResponseEntity<?> provision(
            @PathVariable String revealId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        return provisionOrRotate(revealId, authHeader, false);
    }

    @PostMapping("/reveals/{revealId}/public-link/rotate")
    public ResponseEntity<?> rotate(
            @PathVariable String revealId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        return provisionOrRotate(revealId, authHeader, true);
    }

    private ResponseEntity<?> provisionOrRotate(String revealId, String authHeader, boolean rotate) {
        if (!UUID_PATTERN.matcher(revealId).matches()) {
            return error(HttpStatus.NOT_FOUND, "reveal_not_found");
        }

        Map<String, Object> doc = firebaseService.getRevealData(revealId);
        if (doc == null) {
            return error(HttpStatus.NOT_FOUND, "reveal_not_found");
        }
        if (!isAuthorized(authHeader, doc)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        PublicLinkService.ProvisionResult result =
                rotate ? publicLinkService.rotate(revealId) : publicLinkService.provision(revealId);
        if (result == null) {
            return error(HttpStatus.NOT_FOUND, "reveal_not_found");
        }
        return ResponseEntity.ok()
                .cacheControl(NO_STORE)
                .body(PublicLinkResponse.of(result.revealId(), result.publicToken(), result.tokenVersion()));
    }

    /** Owner (Firebase ID token, ownerId or legacy createdBy) OR internal service secret. */
    private boolean isAuthorized(String authHeader, Map<String, Object> doc) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        String bearer = authHeader.substring(7).trim();

        if (!internalSecret.isBlank() && MessageDigest.isEqual(
                bearer.getBytes(StandardCharsets.UTF_8),
                internalSecret.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }

        try {
            String uid = FirebaseAuth.getInstance().verifyIdToken(bearer).getUid();
            return uid.equals(doc.get("ownerId")) || uid.equals(doc.get("createdBy"));
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Guest-safe state (the ONLY guest path returning the result) ----------

    @GetMapping("/public/reveals/{publicToken}")
    public ResponseEntity<?> publicState(
            @PathVariable String publicToken,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor
    ) {
        if (rateLimited(forwardedFor, publicToken)) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }
        if (!publicLinkService.isValidTokenFormat(publicToken)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_token");
        }

        Optional<String> revealId = publicLinkService.resolve(publicToken);
        if (revealId.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "reveal_not_found");
        }

        try {
            Map<String, Object> doc = firebaseService.getRevealData(revealId.get());
            if (doc == null) {
                return error(HttpStatus.NOT_FOUND, "reveal_not_found");
            }

            var stats = seatService.stats(revealId.get());
            long joined = stats != null ? stats.joined() : 0;
            boolean gateOpen = stats == null || stats.limit() == null || joined < stats.limit();

            PublicRevealState state = PublicRevealState.from(
                    revealId.get(), doc, joined, gateOpen, Instant.now());
            return ResponseEntity.ok().cacheControl(NO_STORE).body(state);
        } catch (Exception e) {
            log.error("Public state failed: tokenFp={}", fp(publicToken), e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "reveal_unavailable");
        }
    }

    // ---------- Legacy link resolution (migration §8.4) ----------

    public record LegacyResolveRequest(String encoded) {}

    /**
     * Decodes a legacy /reveal/{base64-json} payload SERVER-SIDE, extracts only
     * the bsid, and returns the opaque path. The gender field in the legacy
     * payload is never read, decoded, or echoed.
     */
    @PostMapping("/public/legacy-resolve")
    public ResponseEntity<?> legacyResolve(
            @RequestBody LegacyResolveRequest request,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor
    ) {
        if (rateLimited(forwardedFor, "legacy")) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }
        String bsid = extractBsid(request.encoded());
        if (bsid == null) {
            return error(HttpStatus.NOT_FOUND, "reveal_not_found");
        }

        // Backend-created reveals: docId == bsid. Ancient client-created docs
        // may hold the UUID in sessionId/backendSessionId fields instead.
        String docId = firebaseService.findRevealDocIdForSession(bsid);
        if (docId == null) {
            return error(HttpStatus.NOT_FOUND, "reveal_not_found");
        }

        PublicLinkService.ProvisionResult result = publicLinkService.provision(docId);
        if (result == null) {
            return error(HttpStatus.NOT_FOUND, "reveal_not_found");
        }
        return ResponseEntity.ok()
                .cacheControl(NO_STORE)
                .body(Map.of("publicPath", "/reveal/" + result.publicToken()));
    }

    private String extractBsid(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 2048) {
            return null;
        }
        try {
            String base64 = encoded.replace('-', '+').replace('_', '/');
            byte[] decoded = Base64.getMimeDecoder().decode(base64);
            var node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(new String(decoded, StandardCharsets.UTF_8));
            String bsid = node.path("bsid").asText(null);
            return bsid != null && UUID_PATTERN.matcher(bsid).matches() ? bsid : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- Helpers ----------

    private boolean rateLimited(String forwardedFor, String token) {
        try {
            String ip = clientIp(forwardedFor);
            return redisRepository.isPublicLookupRateLimited(ip, lookupsPerIpPerMinute)
                    || redisRepository.isPublicLookupRateLimited(ip + ":" + fp(token), lookupsPerIpPerMinute / 2);
        } catch (Exception e) {
            // Redis hiccup: fail open — availability beats enforcement for lookups.
            return false;
        }
    }

    private static String clientIp(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return "unknown";
        }
        return forwardedFor.split(",")[0].trim();
    }

    /** Safe log/rate-limit identifier: first 10 chars of the token's SHA-256. Never the token. */
    private static String fp(String token) {
        return PublicLinkService.sha256Hex(token == null ? "" : token).substring(0, 10);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).cacheControl(NO_STORE).body(Map.of("error", code));
    }
}
