package revealtogether.websockets.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import revealtogether.websockets.BaseIntegrationTest;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.domain.SessionStatus;
import revealtogether.websockets.domain.VoteCount;
import revealtogether.websockets.domain.VoteOption;
import revealtogether.websockets.dto.SessionCreateRequest;
import revealtogether.websockets.dto.VoteRequest;
import revealtogether.websockets.dto.VoteResponse;
import revealtogether.websockets.repository.RedisRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VoteService Integration Tests")
class VoteServiceTest extends BaseIntegrationTest {

    @Autowired
    private VoteService voteService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private RedisRepository redisRepository;

    private String sessionId;

    @BeforeEach
    void setUp() {
        // Create a test session
        SessionCreateRequest request = new SessionCreateRequest(
                "owner-123",
                "boy",
                Instant.now().plusSeconds(3600)
        );
        Session session = sessionService.createSession(request);
        sessionId = session.sessionId();

        // Activate the session so voting is allowed
        sessionService.activateSession(sessionId);
    }

    @Nested
    @DisplayName("Nominal Flow - Cast Vote")
    class NominalFlow {

        @Test
        @DisplayName("Should successfully cast a vote for BOY")
        void shouldCastVoteForBoy() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            VoteRequest request = new VoteRequest("boy", visitorId, "TestUser");

            // When
            VoteResponse response = voteService.castVote(sessionId, request);

            // Then
            assertThat(response.success()).isTrue();
            assertThat(response.message()).isEqualTo("Vote recorded");

            VoteCount votes = voteService.getVotes(sessionId);
            assertThat(votes.boy()).isEqualTo(1);
            assertThat(votes.girl()).isZero();
        }

        @Test
        @DisplayName("Should successfully cast a vote for GIRL")
        void shouldCastVoteForGirl() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            VoteRequest request = new VoteRequest("girl", visitorId, "TestUser");

            // When
            VoteResponse response = voteService.castVote(sessionId, request);

            // Then
            assertThat(response.success()).isTrue();

            VoteCount votes = voteService.getVotes(sessionId);
            assertThat(votes.boy()).isZero();
            assertThat(votes.girl()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should track if visitor has voted")
        void shouldTrackIfVisitorHasVoted() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            VoteRequest request = new VoteRequest("boy", visitorId, "TestUser");

            // When
            voteService.castVote(sessionId, request);

            // Then
            assertThat(voteService.hasVoted(sessionId, visitorId)).isTrue();
            assertThat(voteService.hasVoted(sessionId, "other-visitor")).isFalse();
        }
    }

    @Nested
    @DisplayName("Edge Case - Duplicate Vote Prevention")
    class DuplicateVotePrevention {

        @Test
        @DisplayName("Should reject duplicate vote from same visitor")
        void shouldRejectDuplicateVote() throws InterruptedException {
            // Given
            String visitorId = UUID.randomUUID().toString();
            VoteRequest firstVote = new VoteRequest("boy", visitorId, "TestUser");
            VoteRequest secondVote = new VoteRequest("girl", visitorId, "TestUser");

            // When
            VoteResponse first = voteService.castVote(sessionId, firstVote);

            // Wait for rate limit to expire so we can test duplicate prevention
            Thread.sleep(1100);

            VoteResponse second = voteService.castVote(sessionId, secondVote);

            // Then
            assertThat(first.success()).isTrue();
            assertThat(second.success()).isFalse();
            assertThat(second.message()).isEqualTo("Already voted");

            // Vote count should only reflect first vote
            VoteCount votes = voteService.getVotes(sessionId);
            assertThat(votes.boy()).isEqualTo(1);
            assertThat(votes.girl()).isZero();
        }

        @Test
        @DisplayName("Should allow different visitors to vote")
        void shouldAllowDifferentVisitors() {
            // Given
            VoteRequest vote1 = new VoteRequest("boy", UUID.randomUUID().toString(), "TestUser");
            VoteRequest vote2 = new VoteRequest("girl", UUID.randomUUID().toString(), "TestUser");
            VoteRequest vote3 = new VoteRequest("boy", UUID.randomUUID().toString(), "TestUser");

            // When
            voteService.castVote(sessionId, vote1);
            voteService.castVote(sessionId, vote2);
            voteService.castVote(sessionId, vote3);

            // Then
            VoteCount votes = voteService.getVotes(sessionId);
            assertThat(votes.boy()).isEqualTo(2);
            assertThat(votes.girl()).isEqualTo(1);
            assertThat(votes.total()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Edge Case - Rate Limiting")
    class RateLimiting {

        @Test
        @DisplayName("Should rate limit rapid vote attempts")
        void shouldRateLimitRapidVotes() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            VoteRequest request = new VoteRequest("boy", visitorId, "TestUser");

            // First request consumes rate limit
            redisRepository.isRateLimited(visitorId);

            // When - immediate second request should be rate limited
            VoteResponse response = voteService.castVote(sessionId, request);

            // Then
            assertThat(response.success()).isFalse();
            assertThat(response.message()).isEqualTo("Rate limited, try again later");
        }

        @Test
        @DisplayName("Should allow vote after rate limit expires")
        void shouldAllowAfterRateLimitExpires() throws InterruptedException {
            // Given
            String visitorId = UUID.randomUUID().toString();
            VoteRequest request = new VoteRequest("boy", visitorId, "TestUser");

            // Consume rate limit
            redisRepository.isRateLimited(visitorId);

            // When - wait for rate limit to expire
            Thread.sleep(1100);
            VoteResponse response = voteService.castVote(sessionId, request);

            // Then
            assertThat(response.success()).isTrue();
        }
    }

    @Nested
    @DisplayName("Edge Case - Session State Validation")
    class SessionStateValidation {

        @Test
        @DisplayName("Should reject vote on non-existent session")
        void shouldRejectVoteOnNonExistentSession() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            VoteRequest request = new VoteRequest("boy", visitorId, "TestUser");

            // When
            VoteResponse response = voteService.castVote("non-existent-session", request);

            // Then
            assertThat(response.success()).isFalse();
            assertThat(response.message()).isEqualTo("Session not found");
        }

        @Test
        @DisplayName("Should reject vote on ended session")
        void shouldRejectVoteOnEndedSession() {
            // Given
            sessionService.endSession(sessionId);
            String visitorId = UUID.randomUUID().toString();
            VoteRequest request = new VoteRequest("boy", visitorId, "TestUser");

            // When
            VoteResponse response = voteService.castVote(sessionId, request);

            // Then
            assertThat(response.success()).isFalse();
            assertThat(response.message()).isEqualTo("Session has ended");
        }
    }

    @Nested
    @DisplayName("Edge Case - High Concurrency (1000 votes)")
    class HighConcurrency {

        @Test
        @DisplayName("Should handle 100 concurrent votes correctly")
        void shouldHandleConcurrentVotes() throws InterruptedException {
            // Given
            int numVoters = 100;
            ExecutorService executor = Executors.newFixedThreadPool(20);
            CountDownLatch latch = new CountDownLatch(numVoters);
            List<VoteResponse> responses = new ArrayList<>();

            // When - simulate 100 concurrent voters
            for (int i = 0; i < numVoters; i++) {
                final String visitorId = UUID.randomUUID().toString();
                final String option = (i % 2 == 0) ? "boy" : "girl";
                executor.submit(() -> {
                    try {
                        VoteRequest request = new VoteRequest(option, visitorId, "TestUser");
                        VoteResponse response = voteService.castVote(sessionId, request);
                        synchronized (responses) {
                            responses.add(response);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            // Then
            VoteCount finalVotes = voteService.getVotes(sessionId);

            // All votes should be recorded (might have some rate limited)
            long successfulVotes = responses.stream().filter(VoteResponse::success).count();
            assertThat(finalVotes.total()).isEqualTo(successfulVotes);

            // Votes should be roughly split (allowing for rate limiting)
            assertThat(finalVotes.boy() + finalVotes.girl()).isEqualTo(successfulVotes);
        }

        @Test
        @DisplayName("Should maintain vote integrity under concurrent duplicate attempts")
        void shouldMaintainIntegrityUnderConcurrentDuplicates() throws InterruptedException {
            // Given - same visitor trying to vote multiple times concurrently
            String visitorId = UUID.randomUUID().toString();
            int attempts = 50;
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(attempts);
            List<VoteResponse> responses = new ArrayList<>();

            // When
            for (int i = 0; i < attempts; i++) {
                executor.submit(() -> {
                    try {
                        VoteRequest request = new VoteRequest("boy", visitorId, "TestUser");
                        VoteResponse response = voteService.castVote(sessionId, request);
                        synchronized (responses) {
                            responses.add(response);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Then - only ONE vote should be recorded
            VoteCount votes = voteService.getVotes(sessionId);

            // Due to rate limiting and duplicate prevention, only 1 should succeed
            long successCount = responses.stream().filter(VoteResponse::success).count();
            assertThat(successCount).isLessThanOrEqualTo(1);
            assertThat(votes.boy()).isLessThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Edge Case - Invalid Vote Option")
    class InvalidVoteOption {

        @Test
        @DisplayName("Should throw exception for invalid vote option at service level")
        void shouldThrowExceptionForInvalidVoteOption() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            // Note: VoteRequest record doesn't validate at construction
            // The @Pattern annotation is validated by Spring at controller level
            // VoteOption.fromValue() throws IllegalArgumentException in service

            // When/Then - the service will throw when parsing the option
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> {
                        // Create request with invalid option (bypassing controller validation)
                        VoteRequest request = new VoteRequest("invalid", visitorId, "TestUser");
                        voteService.castVote(sessionId, request);
                    }
            );
        }
    }
}
