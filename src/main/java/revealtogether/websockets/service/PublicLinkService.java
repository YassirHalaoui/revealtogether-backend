package revealtogether.websockets.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import revealtogether.websockets.repository.RedisRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Opaque public reveal links (opaqueRevealLinksV1).
 *
 * Token: "rt_" + base64url(24 secure-random bytes) = 192 bits of entropy,
 * no padding. The token encodes NOTHING — the only way from token to reveal
 * is the SHA-256 lookup below.
 *
 * Storage: reveals/{id}.publicToken (raw, owner/server-only once Firestore
 * rules are locked) + publicTokenHash (hex, indexed for lookup) +
 * tokenVersion. Raw tokens are NEVER logged — fingerprint() only.
 */
@Service
public class PublicLinkService {

    private static final Logger log = LoggerFactory.getLogger(PublicLinkService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 24;
    public static final String TOKEN_PREFIX = "rt_";
    // base64url of 24 bytes = 32 chars; total = rt_ + 32
    private static final int TOKEN_LENGTH = TOKEN_PREFIX.length() + 32;

    private final FirebaseService firebaseService;
    private final RedisRepository redisRepository;

    public PublicLinkService(FirebaseService firebaseService, RedisRepository redisRepository) {
        this.firebaseService = firebaseService;
        this.redisRepository = redisRepository;
    }

    public record ProvisionResult(String revealId, String publicToken, int tokenVersion, boolean created) {}

    /**
     * Idempotent by natural key: one active token per reveal. Repeat calls
     * return the existing token; rotation is an explicit separate operation.
     * @return null if the reveal doesn't exist.
     */
    public ProvisionResult provision(String revealId) {
        Map<String, Object> doc = firebaseService.getRevealData(revealId);
        if (doc == null) {
            return null;
        }

        String existing = (String) doc.get("publicToken");
        if (existing != null && !existing.isBlank()) {
            int version = intOrDefault(doc.get("tokenVersion"), 1);
            return new ProvisionResult(revealId, existing, version, false);
        }

        String token = generateToken();
        String hash = sha256Hex(token);
        firebaseService.savePublicLink(revealId, token, hash, 1, false);
        redisRepository.cachePublicToken(hash, revealId);
        log.info("Public link provisioned: reveal={}, tokenFp={}", revealId, fingerprint(hash));
        return new ProvisionResult(revealId, token, 1, true);
    }

    /**
     * Atomically replaces token + hash (single Firestore doc update), bumps
     * tokenVersion, then invalidates the old hash in the Redis cache so the
     * previous link dies immediately.
     * @return null if the reveal doesn't exist.
     */
    public ProvisionResult rotate(String revealId) {
        Map<String, Object> doc = firebaseService.getRevealData(revealId);
        if (doc == null) {
            return null;
        }

        String oldToken = (String) doc.get("publicToken");
        int newVersion = intOrDefault(doc.get("tokenVersion"), 1) + 1;

        String token = generateToken();
        String hash = sha256Hex(token);
        firebaseService.savePublicLink(revealId, token, hash, newVersion, true);
        if (oldToken != null && !oldToken.isBlank()) {
            redisRepository.evictPublicToken(sha256Hex(oldToken));
        }
        redisRepository.cachePublicToken(hash, revealId);
        log.info("Public link rotated: reveal={}, version={}, tokenFp={}", revealId, newVersion, fingerprint(hash));
        return new ProvisionResult(revealId, token, newVersion, true);
    }

    /**
     * Token -> revealId. Redis cache first, Firestore hash-equality query on
     * miss. Lookup is by exact hash key, so no variable-time comparison of
     * secret material happens in application code.
     * @return empty for unknown, revoked, or rotated tokens (non-enumerating).
     */
    public Optional<String> resolve(String publicToken) {
        if (!isValidTokenFormat(publicToken)) {
            return Optional.empty();
        }
        String hash = sha256Hex(publicToken);

        String cached = redisRepository.getCachedPublicToken(hash);
        if (cached != null) {
            return Optional.of(cached);
        }

        String revealId = firebaseService.findRevealIdByTokenHash(hash);
        if (revealId == null) {
            log.debug("Public token resolve miss: tokenFp={}", fingerprint(hash));
            return Optional.empty();
        }
        redisRepository.cachePublicToken(hash, revealId);
        return Optional.of(revealId);
    }

    public boolean isValidTokenFormat(String token) {
        if (token == null || token.length() != TOKEN_LENGTH || !token.startsWith(TOKEN_PREFIX)) {
            return false;
        }
        String body = token.substring(TOKEN_PREFIX.length());
        return body.matches("^[A-Za-z0-9_-]+$");
    }

    static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Safe-to-log identifier: first 10 chars of the token HASH, never the token. */
    static String fingerprint(String tokenHash) {
        return tokenHash.length() >= 10 ? tokenHash.substring(0, 10) : tokenHash;
    }

    private static int intOrDefault(Object value, int fallback) {
        Integer parsed = FirebaseService.toNullableInt(value);
        return parsed != null ? parsed : fallback;
    }
}
