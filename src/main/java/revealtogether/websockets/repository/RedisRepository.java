package revealtogether.websockets.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import revealtogether.websockets.domain.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Repository
public class RedisRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisRepository.class);

    private static final String SESSION_KEY = "session:";
    private static final String VOTES_KEY = "votes:";
    private static final String VOTERS_KEY = "voters:";
    private static final String VOTE_RECORDS_KEY = "voterecords:";
    private static final String CHAT_KEY = "chat:";
    private static final String DIRTY_KEY = "dirty:";
    private static final String RATELIMIT_KEY = "ratelimit:";
    private static final String ACTIVE_SESSIONS_KEY = "active_sessions";
    // Tiered-pricing seat model. seats: = seat-consuming visitorIds (SCARD = joined count).
    // seataccess: = ALL participation-allowed visitorIds (seat owners + email-merged devices).
    // emailseat: = sha256(email) -> seat-owning visitorId, for cross-device merge.
    private static final String SEATS_KEY = "seats:";
    private static final String SEAT_ACCESS_KEY = "seataccess:";
    private static final String EMAIL_SEAT_KEY = "emailseat:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration sessionTtl;
    private final Duration postRevealTtl;
    private final int maxChatMessages;

    public RedisRepository(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${app.redis.ttl.session-hours:24}") int sessionTtlHours,
            @Value("${app.redis.ttl.post-reveal-hours:1}") int postRevealTtlHours,
            @Value("${app.chat.max-messages:500}") int maxChatMessages
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.sessionTtl = Duration.ofHours(sessionTtlHours);
        this.postRevealTtl = Duration.ofHours(postRevealTtlHours);
        this.maxChatMessages = maxChatMessages;
    }

    // Session operations

    public void saveSession(Session session) {
        String key = SESSION_KEY + session.sessionId();
        Map<String, String> fields = new HashMap<>();
        fields.put("sessionId", session.sessionId());
        fields.put("ownerId", session.ownerId());
        fields.put("gender", session.gender().getValue());
        fields.put("status", session.status().getValue());
        fields.put("revealTime", session.revealTime().toString());
        fields.put("createdAt", session.createdAt().toString());
        if (session.motherName() != null) fields.put("motherName", session.motherName());
        if (session.fatherName() != null) fields.put("fatherName", session.fatherName());
        if (session.tier() != null) fields.put("tier", session.tier());
        if (session.seatLimit() != null) fields.put("seatLimit", String.valueOf(session.seatLimit()));

        redis.opsForHash().putAll(key, fields);
        redis.expire(key, sessionTtl);

        // Track active session
        redis.opsForSet().add(ACTIVE_SESSIONS_KEY, session.sessionId());
    }

    public Optional<Session> getSession(String sessionId) {
        String key = SESSION_KEY + sessionId;
        Map<Object, Object> fields = redis.opsForHash().entries(key);
        if (fields.isEmpty()) {
            return Optional.empty();
        }

        Integer seatLimit = null;
        Object rawSeatLimit = fields.get("seatLimit");
        if (rawSeatLimit != null) {
            try {
                seatLimit = Integer.parseInt(rawSeatLimit.toString());
            } catch (NumberFormatException e) {
                log.warn("Invalid seatLimit in Redis for session {}: {}", sessionId, rawSeatLimit);
            }
        }

        return Optional.of(new Session(
                (String) fields.get("sessionId"),
                (String) fields.get("ownerId"),
                VoteOption.fromValue((String) fields.get("gender")),
                SessionStatus.fromValue((String) fields.get("status")),
                Instant.parse((String) fields.get("revealTime")),
                Instant.parse((String) fields.get("createdAt")),
                (String) fields.get("motherName"),
                (String) fields.get("fatherName"),
                (String) fields.get("tier"),
                seatLimit
        ));
    }

    public void updateSessionStatus(String sessionId, SessionStatus status) {
        String key = SESSION_KEY + sessionId;
        redis.opsForHash().put(key, "status", status.getValue());
    }

    /**
     * Updates the cached tier/seatLimit after an upgrade (called by refresh-tier).
     * No-op if the session isn't cached — the next lazy load reads Firestore anyway.
     */
    public void updateSessionTier(String sessionId, String tier, Integer seatLimit) {
        String key = SESSION_KEY + sessionId;
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            return;
        }
        if (tier != null) {
            redis.opsForHash().put(key, "tier", tier);
        } else {
            redis.opsForHash().delete(key, "tier");
        }
        if (seatLimit != null) {
            redis.opsForHash().put(key, "seatLimit", String.valueOf(seatLimit));
        } else {
            redis.opsForHash().delete(key, "seatLimit");
        }
    }

    public boolean sessionExists(String sessionId) {
        return Boolean.TRUE.equals(redis.hasKey(SESSION_KEY + sessionId));
    }

    // Vote operations

    public void initializeVotes(String sessionId) {
        String key = VOTES_KEY + sessionId;
        redis.opsForHash().put(key, "boy", "0");
        redis.opsForHash().put(key, "girl", "0");
        redis.expire(key, sessionTtl);
    }

    public void restoreVotes(String sessionId, long boy, long girl) {
        String key = VOTES_KEY + sessionId;
        redis.opsForHash().put(key, "boy", String.valueOf(boy));
        redis.opsForHash().put(key, "girl", String.valueOf(girl));
        redis.expire(key, sessionTtl);
    }

    public void restoreVoter(String sessionId, String visitorId) {
        String key = VOTERS_KEY + sessionId;
        redis.opsForSet().add(key, visitorId);
        redis.expire(key, sessionTtl);
    }

    public void restoreVoteRecords(String sessionId, List<VoteRecord> records) {
        String key = VOTE_RECORDS_KEY + sessionId;
        // saveVoteRecord uses leftPush so index 0 = newest. To restore the same layout,
        // push oldest-first (iterate in reverse) so newest ends up at index 0.
        for (int i = records.size() - 1; i >= 0; i--) {
            try {
                String json = objectMapper.writeValueAsString(records.get(i));
                redis.opsForList().leftPush(key, json);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize vote record during restore", e);
            }
        }
        // Cap at 100 to match saveVoteRecord behaviour
        redis.opsForList().trim(key, 0, 99);
        redis.expire(key, sessionTtl);
    }

    public boolean hasVoted(String sessionId, String visitorId) {
        return Boolean.TRUE.equals(
                redis.opsForSet().isMember(VOTERS_KEY + sessionId, visitorId)
        );
    }

    public boolean recordVote(String sessionId, String visitorId, VoteOption option) {
        String votersKey = VOTERS_KEY + sessionId;

        // Add to voters set (returns 1 if new, 0 if already exists)
        Long added = redis.opsForSet().add(votersKey, visitorId);
        if (added == null || added == 0) {
            return false; // Already voted
        }

        redis.expire(votersKey, sessionTtl);

        // Increment vote count
        String votesKey = VOTES_KEY + sessionId;
        redis.opsForHash().increment(votesKey, option.getValue(), 1);

        // Mark as dirty for broadcast
        markDirty(sessionId);

        return true;
    }

    public VoteCount getVotes(String sessionId) {
        String key = VOTES_KEY + sessionId;
        Map<Object, Object> fields = redis.opsForHash().entries(key);

        long boy = parseLong(fields.get("boy"));
        long girl = parseLong(fields.get("girl"));

        return new VoteCount(boy, girl);
    }

    // Vote records (individual votes with names)

    public void saveVoteRecord(String sessionId, VoteRecord record) {
        String key = VOTE_RECORDS_KEY + sessionId;
        try {
            String json = objectMapper.writeValueAsString(record);
            redis.opsForList().leftPush(key, json);
            // Keep last 100 vote records for display
            redis.opsForList().trim(key, 0, 99);
            redis.expire(key, sessionTtl);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize vote record", e);
        }
    }

    public List<VoteRecord> getRecentVotes(String sessionId, int limit) {
        String key = VOTE_RECORDS_KEY + sessionId;
        List<String> jsonRecords = redis.opsForList().range(key, 0, limit - 1);
        if (jsonRecords == null) {
            return List.of();
        }

        List<VoteRecord> records = new ArrayList<>();
        for (String json : jsonRecords) {
            try {
                records.add(objectMapper.readValue(json, VoteRecord.class));
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize vote record", e);
            }
        }

        // Reverse to get chronological order (oldest first)
        Collections.reverse(records);
        return records;
    }

    // Seat operations (tiered pricing)

    /**
     * Claims a fresh seat: the visitorId is added to both the counting set and
     * the access set. NOT atomic with the capacity check in SeatService — the
     * grace buffer (+20%) absorbs the tiny concurrent-join race by design.
     */
    public void claimSeat(String sessionId, String visitorId) {
        redis.opsForSet().add(SEATS_KEY + sessionId, visitorId);
        redis.opsForSet().add(SEAT_ACCESS_KEY + sessionId, visitorId);
        redis.expire(SEATS_KEY + sessionId, sessionTtl);
        redis.expire(SEAT_ACCESS_KEY + sessionId, sessionTtl);
    }

    /** Grants participation without consuming a seat (email-merged extra device). */
    public void grantSeatAccess(String sessionId, String visitorId) {
        redis.opsForSet().add(SEAT_ACCESS_KEY + sessionId, visitorId);
        redis.expire(SEAT_ACCESS_KEY + sessionId, sessionTtl);
    }

    public boolean hasSeatAccess(String sessionId, String visitorId) {
        return Boolean.TRUE.equals(
                redis.opsForSet().isMember(SEAT_ACCESS_KEY + sessionId, visitorId)
        );
    }

    /** True if this visitorId consumed a counting seat (vs email-merged access). */
    public boolean isCountedSeat(String sessionId, String visitorId) {
        return Boolean.TRUE.equals(
                redis.opsForSet().isMember(SEATS_KEY + sessionId, visitorId)
        );
    }

    public long seatCount(String sessionId) {
        Long count = redis.opsForSet().size(SEATS_KEY + sessionId);
        return count != null ? count : 0;
    }

    public String getSeatOwnerByEmailHash(String sessionId, String emailHash) {
        Object owner = redis.opsForHash().get(EMAIL_SEAT_KEY + sessionId, emailHash);
        return owner != null ? owner.toString() : null;
    }

    public void mapEmailToSeat(String sessionId, String emailHash, String visitorId) {
        redis.opsForHash().put(EMAIL_SEAT_KEY + sessionId, emailHash, visitorId);
        redis.expire(EMAIL_SEAT_KEY + sessionId, sessionTtl);
    }

    /** Rebuilds all seat structures from Firestore records during lazy reload. */
    public void restoreSeats(String sessionId, List<SeatRecord> records) {
        for (SeatRecord record : records) {
            if (record.countsAsSeat()) {
                redis.opsForSet().add(SEATS_KEY + sessionId, record.visitorId());
                if (record.emailHash() != null) {
                    redis.opsForHash().put(EMAIL_SEAT_KEY + sessionId, record.emailHash(), record.visitorId());
                }
            }
            redis.opsForSet().add(SEAT_ACCESS_KEY + sessionId, record.visitorId());
        }
        redis.expire(SEATS_KEY + sessionId, sessionTtl);
        redis.expire(SEAT_ACCESS_KEY + sessionId, sessionTtl);
        redis.expire(EMAIL_SEAT_KEY + sessionId, sessionTtl);
    }

    // Chat operations

    public void addChatMessage(String sessionId, ChatMessage message) {
        String key = CHAT_KEY + sessionId;
        try {
            String json = objectMapper.writeValueAsString(message);
            redis.opsForList().leftPush(key, json);
            redis.opsForList().trim(key, 0, maxChatMessages - 1);
            redis.expire(key, sessionTtl);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize chat message", e);
        }
    }

    public List<ChatMessage> getRecentMessages(String sessionId, int limit) {
        String key = CHAT_KEY + sessionId;
        List<String> jsonMessages = redis.opsForList().range(key, 0, limit - 1);
        if (jsonMessages == null) {
            return List.of();
        }

        List<ChatMessage> messages = new ArrayList<>();
        for (String json : jsonMessages) {
            try {
                messages.add(objectMapper.readValue(json, ChatMessage.class));
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize chat message", e);
            }
        }

        // Reverse to get chronological order (oldest first)
        Collections.reverse(messages);
        return messages;
    }

    public List<ChatMessage> getAllMessages(String sessionId) {
        return getRecentMessages(sessionId, maxChatMessages);
    }

    // Dirty flag for batched broadcasts

    public void markDirty(String sessionId) {
        redis.opsForValue().set(DIRTY_KEY + sessionId, "1", sessionTtl);
    }

    public boolean isDirtyAndClear(String sessionId) {
        String key = DIRTY_KEY + sessionId;
        String value = redis.opsForValue().getAndDelete(key);
        return "1".equals(value);
    }

    // Opaque public link support (opaqueRevealLinksV1)

    /** Cache: sha256(token) -> revealId. Keyed by hash so raw tokens never touch Redis. */
    public void cachePublicToken(String tokenHash, String revealId) {
        redis.opsForValue().set("pubtoken:" + tokenHash, revealId, sessionTtl);
    }

    public String getCachedPublicToken(String tokenHash) {
        return redis.opsForValue().get("pubtoken:" + tokenHash);
    }

    public void evictPublicToken(String tokenHash) {
        redis.delete("pubtoken:" + tokenHash);
    }

    /**
     * Public-endpoint rate limit: sliding 60s counter per key (IP or
     * IP+fingerprint). Generous limits so one NAT'd party never trips it.
     */
    public boolean isPublicLookupRateLimited(String key, int maxPerMinute) {
        String counterKey = "pubrl:" + key;
        Long count = redis.opsForValue().increment(counterKey);
        if (count != null && count == 1) {
            redis.expire(counterKey, Duration.ofSeconds(60));
        }
        return count != null && count > maxPerMinute;
    }

    /**
     * Once-only guard for the reveal release. @Scheduled ticks share a thread
     * pool, so a slow tick could overlap the next and double-fire the trigger;
     * SETNX makes the transition idempotent across threads and restarts.
     */
    public boolean markRevealFired(String sessionId) {
        Boolean first = redis.opsForValue().setIfAbsent("revealfired:" + sessionId, "1", sessionTtl);
        return Boolean.TRUE.equals(first);
    }

    // Rate limiting

    public boolean isRateLimited(String visitorId) {
        String key = RATELIMIT_KEY + visitorId;
        Boolean exists = redis.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            return true;
        }
        redis.opsForValue().set(key, "1", Duration.ofSeconds(1));
        return false;
    }

    // Active sessions

    public Set<String> getActiveSessions() {
        Set<String> sessions = redis.opsForSet().members(ACTIVE_SESSIONS_KEY);
        return sessions != null ? sessions : Set.of();
    }

    public void removeActiveSession(String sessionId) {
        redis.opsForSet().remove(ACTIVE_SESSIONS_KEY, sessionId);
    }

    // Cleanup

    /**
     * Deletes all Redis keys for a session. Safe to call even if keys don't exist.
     */
    public void deleteSession(String sessionId) {
        redis.delete(SESSION_KEY + sessionId);
        redis.delete(VOTES_KEY + sessionId);
        redis.delete(VOTERS_KEY + sessionId);
        redis.delete(VOTE_RECORDS_KEY + sessionId);
        redis.delete(CHAT_KEY + sessionId);
        redis.delete(DIRTY_KEY + sessionId);
        redis.delete(SEATS_KEY + sessionId);
        redis.delete(SEAT_ACCESS_KEY + sessionId);
        redis.delete(EMAIL_SEAT_KEY + sessionId);
        removeActiveSession(sessionId);
        log.info("Deleted all Redis keys for session {}", sessionId);
    }

    public void setPostRevealTtl(String sessionId) {
        redis.expire(SESSION_KEY + sessionId, postRevealTtl);
        redis.expire(VOTES_KEY + sessionId, postRevealTtl);
        redis.expire(VOTERS_KEY + sessionId, postRevealTtl);
        redis.expire(VOTE_RECORDS_KEY + sessionId, postRevealTtl);
        redis.expire(CHAT_KEY + sessionId, postRevealTtl);
        redis.expire(SEATS_KEY + sessionId, postRevealTtl);
        redis.expire(SEAT_ACCESS_KEY + sessionId, postRevealTtl);
        redis.expire(EMAIL_SEAT_KEY + sessionId, postRevealTtl);
    }

    private long parseLong(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
