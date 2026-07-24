package revealtogether.websockets.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;

/**
 * WP2 — idempotency store.
 *
 * Records (key, actor, endpoint) → the response that was produced, so a retried
 * command produces exactly one effect. Retention ≥48h per spec.
 *
 * Same key + same request body  → replay the stored response.
 * Same key + DIFFERENT body     → 409 IDEMPOTENCY_CONFLICT (a real client bug;
 *                                 silently replaying would hide it).
 *
 * Redis-backed. If Redis is unavailable the filter fails open — dropping
 * idempotency degrades a retry into a duplicate, while failing closed would
 * take mutations down entirely.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String KEY_PREFIX = "idem:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public IdempotencyService(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${app.command.idempotency-ttl-hours:48}") int ttlHours
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofHours(ttlHours);
    }

    public record StoredResponse(int status, String body, String requestHash) {}

    private String redisKey(CommandEnvelope env, String method, String path) {
        return KEY_PREFIX + CommandEnvelope.sha256Short(
                env.idempotencyKey() + "|" + env.actorKey() + "|" + method + "|" + path);
    }

    /** Marks the request in-flight. False = a record already exists (see find). */
    public boolean tryClaim(CommandEnvelope env, String method, String path, String requestHash) {
        try {
            StoredResponse claim = new StoredResponse(0, null, requestHash);
            Boolean claimed = redis.opsForValue().setIfAbsent(
                    redisKey(env, method, path), objectMapper.writeValueAsString(claim), ttl);
            return Boolean.TRUE.equals(claimed);
        } catch (Exception e) {
            log.warn("Idempotency claim failed (failing open): {}", e.getMessage());
            return true;
        }
    }

    public Optional<StoredResponse> find(CommandEnvelope env, String method, String path) {
        try {
            String raw = redis.opsForValue().get(redisKey(env, method, path));
            if (raw == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(raw, StoredResponse.class));
        } catch (Exception e) {
            log.warn("Idempotency lookup failed (failing open): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Stores the completed response so later replays return it verbatim. */
    public void store(CommandEnvelope env, String method, String path,
                      String requestHash, int status, String body) {
        try {
            // Only successful commands are worth replaying — a failed attempt
            // should be retryable with the same key.
            if (status < 200 || status >= 300) {
                redis.delete(redisKey(env, method, path));
                return;
            }
            redis.opsForValue().set(
                    redisKey(env, method, path),
                    objectMapper.writeValueAsString(new StoredResponse(status, body, requestHash)),
                    ttl);
        } catch (Exception e) {
            log.warn("Idempotency store failed: {}", e.getMessage());
        }
    }

    /** Releases an in-flight claim so a failed request can be retried. */
    public void release(CommandEnvelope env, String method, String path) {
        try {
            redis.delete(redisKey(env, method, path));
        } catch (Exception ignored) {
            // TTL will clear it.
        }
    }

    public static String hashBody(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body == null ? new byte[0] : body);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String hashBody(String body) {
        return hashBody(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }
}
