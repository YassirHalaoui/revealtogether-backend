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

        return Optional.of(new Session(
                (String) fields.get("sessionId"),
                (String) fields.get("ownerId"),
                VoteOption.fromValue((String) fields.get("gender")),
                SessionStatus.fromValue((String) fields.get("status")),
                Instant.parse((String) fields.get("revealTime")),
                Instant.parse((String) fields.get("createdAt"))
        ));
    }

    public void updateSessionStatus(String sessionId, SessionStatus status) {
        String key = SESSION_KEY + sessionId;
        redis.opsForHash().put(key, "status", status.getValue());
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

    public void setPostRevealTtl(String sessionId) {
        redis.expire(SESSION_KEY + sessionId, postRevealTtl);
        redis.expire(VOTES_KEY + sessionId, postRevealTtl);
        redis.expire(VOTERS_KEY + sessionId, postRevealTtl);
        redis.expire(VOTE_RECORDS_KEY + sessionId, postRevealTtl);
        redis.expire(CHAT_KEY + sessionId, postRevealTtl);
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
