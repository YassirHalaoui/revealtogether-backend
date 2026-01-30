package revealtogether.websockets.scheduler;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import revealtogether.websockets.BaseIntegrationTest;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.domain.SessionStatus;
import revealtogether.websockets.dto.SessionCreateRequest;
import revealtogether.websockets.dto.VoteRequest;
import revealtogether.websockets.repository.RedisRepository;
import revealtogether.websockets.service.SessionService;
import revealtogether.websockets.service.VoteService;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Scheduler Integration Tests")
class SchedulerTest extends BaseIntegrationTest {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private VoteService voteService;

    @Autowired
    private RedisRepository redisRepository;

    @Autowired
    private VoteBroadcastScheduler voteBroadcastScheduler;

    @Autowired
    private RevealScheduler revealScheduler;

    @Nested
    @DisplayName("VoteBroadcastScheduler")
    class VoteBroadcastTests {

        private String sessionId;

        @BeforeEach
        void setUp() {
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(3600)
            );
            Session session = sessionService.createSession(request);
            sessionId = session.sessionId();
            sessionService.activateSession(sessionId);
        }

        @Test
        @DisplayName("Should clear dirty flag after broadcast")
        void shouldClearDirtyFlagAfterBroadcast() {
            // Given - mark session as dirty
            redisRepository.markDirty(sessionId);

            // When - trigger broadcast
            voteBroadcastScheduler.broadcastVotes();

            // Then - dirty flag should be cleared
            assertThat(redisRepository.isDirtyAndClear(sessionId)).isFalse();
        }

        @Test
        @DisplayName("Should not broadcast when session is not dirty")
        void shouldNotBroadcastWhenNotDirty() {
            // Given - no votes cast, session not dirty
            // When
            voteBroadcastScheduler.broadcastVotes();

            // Then - no errors, dirty flag remains unset
            assertThat(redisRepository.isDirtyAndClear(sessionId)).isFalse();
        }

        @Test
        @DisplayName("Should handle multiple active sessions")
        void shouldHandleMultipleActiveSessions() {
            // Given - create multiple sessions
            Session session2 = sessionService.createSession(new SessionCreateRequest(
                    "owner-456", "girl", Instant.now().plusSeconds(3600)
            ));
            sessionService.activateSession(session2.sessionId());

            // Mark both dirty
            redisRepository.markDirty(sessionId);
            redisRepository.markDirty(session2.sessionId());

            // When
            voteBroadcastScheduler.broadcastVotes();

            // Then - both should be cleared
            assertThat(redisRepository.isDirtyAndClear(sessionId)).isFalse();
            assertThat(redisRepository.isDirtyAndClear(session2.sessionId())).isFalse();
        }
    }

    @Nested
    @DisplayName("RevealScheduler")
    class RevealSchedulerTests {

        @Test
        @DisplayName("Should activate session before reveal time")
        void shouldActivateSessionBeforeRevealTime() {
            // Given - session with reveal time in near future (< 5 minutes)
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(60) // 1 minute from now
            );
            Session session = sessionService.createSession(request);
            assertThat(session.status()).isEqualTo(SessionStatus.WAITING);

            // When - trigger scheduler check
            revealScheduler.checkReveals();

            // Then - session should be activated (since reveal time is < 5 minutes away)
            Optional<Session> updated = sessionService.getSession(session.sessionId());
            assertThat(updated).isPresent();
            assertThat(updated.get().status()).isEqualTo(SessionStatus.LIVE);
        }

        @Test
        @DisplayName("Should not activate session if reveal time is far in future")
        void shouldNotActivateSessionEarly() {
            // Given - session with reveal time far in future (> 5 minutes)
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(600) // 10 minutes from now
            );
            Session session = sessionService.createSession(request);

            // When
            revealScheduler.checkReveals();

            // Then - session should still be waiting
            Optional<Session> updated = sessionService.getSession(session.sessionId());
            assertThat(updated).isPresent();
            assertThat(updated.get().status()).isEqualTo(SessionStatus.WAITING);
        }

        @Test
        @DisplayName("Should end session when reveal time passes")
        void shouldEndSessionWhenRevealTimePasses() {
            // Given - session with reveal time in the past
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "girl",
                    Instant.now().minusSeconds(1) // Already passed
            );
            // Manually create session with past time (bypassing validation for test)
            Session session = sessionService.createSession(new SessionCreateRequest(
                    "owner-123", "girl", Instant.now().plusSeconds(1)
            ));
            String sessionId = session.sessionId();
            sessionService.activateSession(sessionId);

            // Wait for reveal time to pass
            Awaitility.await()
                    .atMost(Duration.ofSeconds(3))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        revealScheduler.checkReveals();
                        Optional<Session> updated = sessionService.getSession(sessionId);
                        return updated.isPresent() && updated.get().status() == SessionStatus.ENDED;
                    });

            // Then
            Optional<Session> finalState = sessionService.getSession(sessionId);
            assertThat(finalState).isPresent();
            assertThat(finalState.get().status()).isEqualTo(SessionStatus.ENDED);
        }

        @Test
        @DisplayName("Should remove session from active sessions when ended")
        void shouldRemoveFromActiveSessionsWhenEnded() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(1)
            );
            Session session = sessionService.createSession(request);
            sessionService.activateSession(session.sessionId());

            assertThat(redisRepository.getActiveSessions()).contains(session.sessionId());

            // When - wait for reveal and trigger scheduler
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        revealScheduler.checkReveals();
                        return !redisRepository.getActiveSessions().contains(session.sessionId());
                    });

            // Then
            assertThat(redisRepository.getActiveSessions()).doesNotContain(session.sessionId());
        }
    }

    @Nested
    @DisplayName("Edge Case - Owner Closes Browser (Session Continues)")
    class OwnerClosesBrowser {

        @Test
        @DisplayName("Reveal should continue even without owner interaction")
        void revealShouldContinueWithoutOwner() {
            // Given - session created, then "owner leaves"
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "girl",
                    Instant.now().plusSeconds(1)
            );
            Session session = sessionService.createSession(request);
            sessionService.activateSession(session.sessionId());

            // Simulate some guests voting
            for (int i = 0; i < 5; i++) {
                voteService.castVote(session.sessionId(),
                        new VoteRequest("boy", UUID.randomUUID().toString(), "TestUser"));
            }

            // Owner "closes browser" - no more interaction

            // When - time passes and scheduler runs
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        revealScheduler.checkReveals();
                        Optional<Session> updated = sessionService.getSession(session.sessionId());
                        return updated.isPresent() && updated.get().status() == SessionStatus.ENDED;
                    });

            // Then - session should have ended automatically
            Optional<Session> finalState = sessionService.getSession(session.sessionId());
            assertThat(finalState).isPresent();
            assertThat(finalState.get().status()).isEqualTo(SessionStatus.ENDED);
        }
    }
}
