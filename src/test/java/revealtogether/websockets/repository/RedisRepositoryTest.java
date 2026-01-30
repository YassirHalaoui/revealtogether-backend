package revealtogether.websockets.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import revealtogether.websockets.BaseIntegrationTest;
import revealtogether.websockets.domain.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RedisRepository Integration Tests")
class RedisRepositoryTest extends BaseIntegrationTest {

    @Autowired
    private RedisRepository redisRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String sessionId;
    private String visitorId;

    @BeforeEach
    void setUp() {
        sessionId = UUID.randomUUID().toString();
        visitorId = UUID.randomUUID().toString();

        // Clean up any existing test data
        Set<String> keys = redisTemplate.keys("*:" + sessionId);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Nested
    @DisplayName("Session Operations")
    class SessionOperations {

        @Test
        @DisplayName("Should save and retrieve session")
        void shouldSaveAndRetrieveSession() {
            // Given
            Session session = createTestSession();

            // When
            redisRepository.saveSession(session);
            Optional<Session> retrieved = redisRepository.getSession(sessionId);

            // Then
            assertThat(retrieved).isPresent();
            assertThat(retrieved.get().sessionId()).isEqualTo(sessionId);
            assertThat(retrieved.get().gender()).isEqualTo(VoteOption.BOY);
            assertThat(retrieved.get().status()).isEqualTo(SessionStatus.WAITING);
        }

        @Test
        @DisplayName("Should return empty for non-existent session")
        void shouldReturnEmptyForNonExistentSession() {
            // When
            Optional<Session> retrieved = redisRepository.getSession("non-existent");

            // Then
            assertThat(retrieved).isEmpty();
        }

        @Test
        @DisplayName("Should update session status")
        void shouldUpdateSessionStatus() {
            // Given
            Session session = createTestSession();
            redisRepository.saveSession(session);

            // When
            redisRepository.updateSessionStatus(sessionId, SessionStatus.LIVE);
            Optional<Session> retrieved = redisRepository.getSession(sessionId);

            // Then
            assertThat(retrieved).isPresent();
            assertThat(retrieved.get().status()).isEqualTo(SessionStatus.LIVE);
        }

        @Test
        @DisplayName("Should track active sessions")
        void shouldTrackActiveSessions() {
            // Given
            Session session = createTestSession();

            // When
            redisRepository.saveSession(session);
            Set<String> activeSessions = redisRepository.getActiveSessions();

            // Then
            assertThat(activeSessions).contains(sessionId);
        }

        @Test
        @DisplayName("Should remove from active sessions")
        void shouldRemoveFromActiveSessions() {
            // Given
            Session session = createTestSession();
            redisRepository.saveSession(session);

            // When
            redisRepository.removeActiveSession(sessionId);
            Set<String> activeSessions = redisRepository.getActiveSessions();

            // Then
            assertThat(activeSessions).doesNotContain(sessionId);
        }
    }

    @Nested
    @DisplayName("Vote Operations")
    class VoteOperations {

        @Test
        @DisplayName("Should initialize votes with zero counts")
        void shouldInitializeVotesWithZeroCounts() {
            // When
            redisRepository.initializeVotes(sessionId);
            VoteCount votes = redisRepository.getVotes(sessionId);

            // Then
            assertThat(votes.boy()).isZero();
            assertThat(votes.girl()).isZero();
        }

        @Test
        @DisplayName("Should record vote and increment count")
        void shouldRecordVoteAndIncrementCount() {
            // Given
            redisRepository.initializeVotes(sessionId);

            // When
            boolean recorded = redisRepository.recordVote(sessionId, visitorId, VoteOption.BOY);
            VoteCount votes = redisRepository.getVotes(sessionId);

            // Then
            assertThat(recorded).isTrue();
            assertThat(votes.boy()).isEqualTo(1);
            assertThat(votes.girl()).isZero();
        }

        @Test
        @DisplayName("Should prevent duplicate votes from same visitor")
        void shouldPreventDuplicateVotes() {
            // Given
            redisRepository.initializeVotes(sessionId);
            redisRepository.recordVote(sessionId, visitorId, VoteOption.BOY);

            // When
            boolean secondVote = redisRepository.recordVote(sessionId, visitorId, VoteOption.GIRL);
            VoteCount votes = redisRepository.getVotes(sessionId);

            // Then
            assertThat(secondVote).isFalse();
            assertThat(votes.boy()).isEqualTo(1);
            assertThat(votes.girl()).isZero();
        }

        @Test
        @DisplayName("Should allow different visitors to vote")
        void shouldAllowDifferentVisitorsToVote() {
            // Given
            redisRepository.initializeVotes(sessionId);
            String visitor1 = UUID.randomUUID().toString();
            String visitor2 = UUID.randomUUID().toString();
            String visitor3 = UUID.randomUUID().toString();

            // When
            redisRepository.recordVote(sessionId, visitor1, VoteOption.BOY);
            redisRepository.recordVote(sessionId, visitor2, VoteOption.GIRL);
            redisRepository.recordVote(sessionId, visitor3, VoteOption.BOY);
            VoteCount votes = redisRepository.getVotes(sessionId);

            // Then
            assertThat(votes.boy()).isEqualTo(2);
            assertThat(votes.girl()).isEqualTo(1);
            assertThat(votes.total()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should check if visitor has voted")
        void shouldCheckIfVisitorHasVoted() {
            // Given
            redisRepository.initializeVotes(sessionId);
            redisRepository.recordVote(sessionId, visitorId, VoteOption.BOY);

            // When/Then
            assertThat(redisRepository.hasVoted(sessionId, visitorId)).isTrue();
            assertThat(redisRepository.hasVoted(sessionId, "other-visitor")).isFalse();
        }

        @Test
        @DisplayName("Should mark session as dirty when vote recorded")
        void shouldMarkDirtyWhenVoteRecorded() {
            // Given
            redisRepository.initializeVotes(sessionId);

            // When
            redisRepository.recordVote(sessionId, visitorId, VoteOption.BOY);

            // Then
            assertThat(redisRepository.isDirtyAndClear(sessionId)).isTrue();
            // Second call should be false (cleared)
            assertThat(redisRepository.isDirtyAndClear(sessionId)).isFalse();
        }
    }

    @Nested
    @DisplayName("Chat Operations")
    class ChatOperations {

        @Test
        @DisplayName("Should add and retrieve chat messages")
        void shouldAddAndRetrieveChatMessages() {
            // Given
            ChatMessage message = ChatMessage.of("Alice", "Hello!", visitorId);

            // When
            redisRepository.addChatMessage(sessionId, message);
            List<ChatMessage> messages = redisRepository.getRecentMessages(sessionId, 50);

            // Then
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).name()).isEqualTo("Alice");
            assertThat(messages.get(0).message()).isEqualTo("Hello!");
        }

        @Test
        @DisplayName("Should return messages in chronological order")
        void shouldReturnMessagesInChronologicalOrder() throws InterruptedException {
            // Given
            ChatMessage msg1 = ChatMessage.of("Alice", "First", visitorId);
            Thread.sleep(10);
            ChatMessage msg2 = ChatMessage.of("Bob", "Second", "visitor-2");
            Thread.sleep(10);
            ChatMessage msg3 = ChatMessage.of("Charlie", "Third", "visitor-3");

            // When
            redisRepository.addChatMessage(sessionId, msg1);
            redisRepository.addChatMessage(sessionId, msg2);
            redisRepository.addChatMessage(sessionId, msg3);
            List<ChatMessage> messages = redisRepository.getRecentMessages(sessionId, 50);

            // Then
            assertThat(messages).hasSize(3);
            assertThat(messages.get(0).name()).isEqualTo("Alice");
            assertThat(messages.get(1).name()).isEqualTo("Bob");
            assertThat(messages.get(2).name()).isEqualTo("Charlie");
        }

        @Test
        @DisplayName("Should limit returned messages")
        void shouldLimitReturnedMessages() {
            // Given
            for (int i = 0; i < 10; i++) {
                redisRepository.addChatMessage(sessionId,
                    ChatMessage.of("User" + i, "Message " + i, "visitor-" + i));
            }

            // When
            List<ChatMessage> messages = redisRepository.getRecentMessages(sessionId, 5);

            // Then
            assertThat(messages).hasSize(5);
        }

        @Test
        @DisplayName("Should return empty list for session without messages")
        void shouldReturnEmptyListForNoMessages() {
            // When
            List<ChatMessage> messages = redisRepository.getRecentMessages(sessionId, 50);

            // Then
            assertThat(messages).isEmpty();
        }
    }

    @Nested
    @DisplayName("Rate Limiting")
    class RateLimiting {

        @Test
        @DisplayName("Should not rate limit first request")
        void shouldNotRateLimitFirstRequest() {
            // When
            boolean limited = redisRepository.isRateLimited(visitorId);

            // Then
            assertThat(limited).isFalse();
        }

        @Test
        @DisplayName("Should rate limit immediate second request")
        void shouldRateLimitImmediateSecondRequest() {
            // Given
            redisRepository.isRateLimited(visitorId);

            // When
            boolean limited = redisRepository.isRateLimited(visitorId);

            // Then
            assertThat(limited).isTrue();
        }

        @Test
        @DisplayName("Should allow request after rate limit expires")
        void shouldAllowAfterRateLimitExpires() throws InterruptedException {
            // Given
            redisRepository.isRateLimited(visitorId);

            // When - wait for rate limit to expire (1 second)
            Thread.sleep(1100);
            boolean limited = redisRepository.isRateLimited(visitorId);

            // Then
            assertThat(limited).isFalse();
        }

        @Test
        @DisplayName("Should rate limit different visitors independently")
        void shouldRateLimitIndependently() {
            // Given
            String visitor1 = UUID.randomUUID().toString();
            String visitor2 = UUID.randomUUID().toString();

            // When
            redisRepository.isRateLimited(visitor1);
            boolean visitor1Limited = redisRepository.isRateLimited(visitor1);
            boolean visitor2Limited = redisRepository.isRateLimited(visitor2);

            // Then
            assertThat(visitor1Limited).isTrue();
            assertThat(visitor2Limited).isFalse();
        }
    }

    @Nested
    @DisplayName("Dirty Flag Operations")
    class DirtyFlagOperations {

        @Test
        @DisplayName("Should set and clear dirty flag")
        void shouldSetAndClearDirtyFlag() {
            // When
            redisRepository.markDirty(sessionId);

            // Then
            assertThat(redisRepository.isDirtyAndClear(sessionId)).isTrue();
            assertThat(redisRepository.isDirtyAndClear(sessionId)).isFalse();
        }

        @Test
        @DisplayName("Should return false for non-dirty session")
        void shouldReturnFalseForNonDirtySession() {
            // When/Then
            assertThat(redisRepository.isDirtyAndClear(sessionId)).isFalse();
        }
    }

    private Session createTestSession() {
        return new Session(
                sessionId,
                "owner-123",
                VoteOption.BOY,
                SessionStatus.WAITING,
                Instant.now().plusSeconds(3600),
                Instant.now()
        );
    }
}
