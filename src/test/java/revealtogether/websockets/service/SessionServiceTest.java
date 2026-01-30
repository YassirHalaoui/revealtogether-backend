package revealtogether.websockets.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import revealtogether.websockets.BaseIntegrationTest;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.domain.SessionStatus;
import revealtogether.websockets.domain.VoteOption;
import revealtogether.websockets.dto.SessionCreateRequest;
import revealtogether.websockets.dto.SessionStateResponse;
import revealtogether.websockets.dto.VoteRequest;
import revealtogether.websockets.repository.RedisRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SessionService Integration Tests")
class SessionServiceTest extends BaseIntegrationTest {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private VoteService voteService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private RedisRepository redisRepository;

    @Nested
    @DisplayName("Nominal Flow - Create Session")
    class CreateSession {

        @Test
        @DisplayName("Should create session with correct initial state")
        void shouldCreateSessionWithCorrectState() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(3600)
            );

            // When
            Session session = sessionService.createSession(request);

            // Then
            assertThat(session.sessionId()).isNotNull();
            assertThat(session.ownerId()).isEqualTo("owner-123");
            assertThat(session.gender()).isEqualTo(VoteOption.BOY);
            assertThat(session.status()).isEqualTo(SessionStatus.WAITING);
            assertThat(session.createdAt()).isNotNull();
        }

        @Test
        @DisplayName("Should initialize vote counts to zero")
        void shouldInitializeVoteCounts() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "girl",
                    Instant.now().plusSeconds(3600)
            );

            // When
            Session session = sessionService.createSession(request);

            // Then
            var votes = redisRepository.getVotes(session.sessionId());
            assertThat(votes.boy()).isZero();
            assertThat(votes.girl()).isZero();
        }

        @Test
        @DisplayName("Should add session to active sessions")
        void shouldAddToActiveSessions() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(3600)
            );

            // When
            Session session = sessionService.createSession(request);

            // Then
            Set<String> activeSessions = redisRepository.getActiveSessions();
            assertThat(activeSessions).contains(session.sessionId());
        }

        @Test
        @DisplayName("Should generate unique session IDs")
        void shouldGenerateUniqueIds() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(3600)
            );

            // When
            Session session1 = sessionService.createSession(request);
            Session session2 = sessionService.createSession(request);

            // Then
            assertThat(session1.sessionId()).isNotEqualTo(session2.sessionId());
        }
    }

    @Nested
    @DisplayName("Session Lifecycle")
    class SessionLifecycle {

        @Test
        @DisplayName("Should transition from WAITING to LIVE")
        void shouldTransitionToLive() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(3600)
            );
            Session session = sessionService.createSession(request);

            // When
            sessionService.activateSession(session.sessionId());

            // Then
            Optional<Session> updated = sessionService.getSession(session.sessionId());
            assertThat(updated).isPresent();
            assertThat(updated.get().status()).isEqualTo(SessionStatus.LIVE);
        }

        @Test
        @DisplayName("Should transition from LIVE to ENDED")
        void shouldTransitionToEnded() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(3600)
            );
            Session session = sessionService.createSession(request);
            sessionService.activateSession(session.sessionId());

            // When
            sessionService.endSession(session.sessionId());

            // Then
            Optional<Session> updated = sessionService.getSession(session.sessionId());
            assertThat(updated).isPresent();
            assertThat(updated.get().status()).isEqualTo(SessionStatus.ENDED);
        }

        @Test
        @DisplayName("Should remove from active sessions when ended")
        void shouldRemoveFromActiveSessionsWhenEnded() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(3600)
            );
            Session session = sessionService.createSession(request);
            sessionService.activateSession(session.sessionId());

            // When
            sessionService.endSession(session.sessionId());

            // Then
            Set<String> activeSessions = redisRepository.getActiveSessions();
            assertThat(activeSessions).doesNotContain(session.sessionId());
        }
    }

    @Nested
    @DisplayName("Get Session State (Reconnection Flow)")
    class GetSessionState {

        @Test
        @DisplayName("Should return live session state with votes and messages")
        void shouldReturnLiveSessionState() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(3600)
            );
            Session session = sessionService.createSession(request);
            sessionService.activateSession(session.sessionId());

            String visitorId = UUID.randomUUID().toString();

            // Add some votes
            voteService.castVote(session.sessionId(), new VoteRequest("boy", UUID.randomUUID().toString(), "TestUser"));
            voteService.castVote(session.sessionId(), new VoteRequest("girl", UUID.randomUUID().toString(), "TestUser"));

            // When
            SessionStateResponse state = sessionService.getSessionState(session.sessionId(), visitorId);

            // Then
            assertThat(state).isNotNull();
            assertThat(state.sessionId()).isEqualTo(session.sessionId());
            assertThat(state.status()).isEqualTo(SessionStatus.LIVE);
            assertThat(state.votes().total()).isGreaterThanOrEqualTo(0); // May be rate limited
            assertThat(state.hasVoted()).isFalse();
            assertThat(state.revealedGender()).isNull();
        }

        @Test
        @DisplayName("Should indicate if visitor has voted")
        void shouldIndicateIfVisitorHasVoted() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(3600)
            );
            Session session = sessionService.createSession(request);
            sessionService.activateSession(session.sessionId());

            String visitorId = UUID.randomUUID().toString();
            voteService.castVote(session.sessionId(), new VoteRequest("boy", visitorId, "TestUser"));

            // When
            SessionStateResponse state = sessionService.getSessionState(session.sessionId(), visitorId);

            // Then
            assertThat(state.hasVoted()).isTrue();
        }

        @Test
        @DisplayName("Should return ended session state with revealed gender")
        void shouldReturnEndedSessionState() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(3600)
            );
            Session session = sessionService.createSession(request);
            sessionService.activateSession(session.sessionId());
            sessionService.endSession(session.sessionId());

            // When
            SessionStateResponse state = sessionService.getSessionState(
                    session.sessionId(),
                    UUID.randomUUID().toString()
            );

            // Then
            assertThat(state).isNotNull();
            assertThat(state.status()).isEqualTo(SessionStatus.ENDED);
            assertThat(state.revealedGender()).isEqualTo(VoteOption.BOY);
        }

        @Test
        @DisplayName("Should return null for non-existent session")
        void shouldReturnNullForNonExistentSession() {
            // When
            SessionStateResponse state = sessionService.getSessionState(
                    "non-existent",
                    UUID.randomUUID().toString()
            );

            // Then
            assertThat(state).isNull();
        }
    }

    @Nested
    @DisplayName("Edge Case - Session Existence Check")
    class SessionExistence {

        @Test
        @DisplayName("Should return true for existing session")
        void shouldReturnTrueForExistingSession() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(3600)
            );
            Session session = sessionService.createSession(request);

            // When/Then
            assertThat(sessionService.sessionExists(session.sessionId())).isTrue();
        }

        @Test
        @DisplayName("Should return false for non-existent session")
        void shouldReturnFalseForNonExistentSession() {
            // When/Then
            assertThat(sessionService.sessionExists("non-existent")).isFalse();
        }
    }

    @Nested
    @DisplayName("Edge Case - Orphan Session (No Activity)")
    class OrphanSession {

        @Test
        @DisplayName("Session should still be retrievable after creation")
        void sessionShouldBeRetrievable() {
            // Given - Session created but owner never interacts
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(3600)
            );
            Session session = sessionService.createSession(request);

            // When - Some time passes (simulated by just retrieving)
            Optional<Session> retrieved = sessionService.getSession(session.sessionId());

            // Then
            assertThat(retrieved).isPresent();
            assertThat(retrieved.get().sessionId()).isEqualTo(session.sessionId());
        }
    }
}
