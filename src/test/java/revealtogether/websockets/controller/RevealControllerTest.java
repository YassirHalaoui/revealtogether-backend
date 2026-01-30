package revealtogether.websockets.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import revealtogether.websockets.BaseIntegrationTest;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.dto.SessionCreateRequest;
import revealtogether.websockets.dto.SessionResponse;
import revealtogether.websockets.dto.SessionStateResponse;
import revealtogether.websockets.service.SessionService;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RevealController Integration Tests")
class RevealControllerTest extends BaseIntegrationTest {

    private WebTestClient webTestClient;

    @Autowired
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Nested
    @DisplayName("POST /api/reveals - Create Reveal Session")
    class CreateReveal {

        @Test
        @DisplayName("Should create reveal session with valid request")
        void shouldCreateRevealSession() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-123",
                    "boy",
                    Instant.now().plusSeconds(3600)
            );

            // When/Then
            webTestClient.post()
                    .uri("/api/reveals")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody(SessionResponse.class)
                    .value(response -> {
                        assertThat(response.sessionId()).isNotNull();
                        assertThat(response.status().getValue()).isEqualTo("waiting");
                        assertThat(response.shareableLink()).isNotNull();
                    });
        }

        @Test
        @DisplayName("Should create reveal session for GIRL")
        void shouldCreateRevealSessionForGirl() {
            // Given
            SessionCreateRequest request = new SessionCreateRequest(
                    "owner-456",
                    "girl",
                    Instant.now().plusSeconds(7200)
            );

            // When/Then
            webTestClient.post()
                    .uri("/api/reveals")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody(SessionResponse.class)
                    .value(response -> assertThat(response.sessionId()).isNotNull());
        }

        @Test
        @DisplayName("Should reject request with missing owner ID")
        void shouldRejectMissingOwnerId() {
            // Given
            Map<String, Object> invalidRequest = new HashMap<>();
            invalidRequest.put("gender", "boy");
            invalidRequest.put("revealTime", Instant.now().plusSeconds(3600).toString());

            // When/Then
            webTestClient.post()
                    .uri("/api/reveals")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(invalidRequest)
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        @Test
        @DisplayName("Should reject request with invalid gender")
        void shouldRejectInvalidGender() {
            // Given
            Map<String, Object> invalidRequest = new HashMap<>();
            invalidRequest.put("ownerId", "owner-123");
            invalidRequest.put("gender", "invalid");
            invalidRequest.put("revealTime", Instant.now().plusSeconds(3600).toString());

            // When/Then
            webTestClient.post()
                    .uri("/api/reveals")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(invalidRequest)
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        @Test
        @DisplayName("Should reject request with past reveal time")
        void shouldRejectPastRevealTime() {
            // Given
            Map<String, Object> invalidRequest = new HashMap<>();
            invalidRequest.put("ownerId", "owner-123");
            invalidRequest.put("gender", "boy");
            invalidRequest.put("revealTime", Instant.now().minusSeconds(3600).toString());

            // When/Then
            webTestClient.post()
                    .uri("/api/reveals")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(invalidRequest)
                    .exchange()
                    .expectStatus().isBadRequest();
        }
    }

    @Nested
    @DisplayName("GET /api/reveals/{sessionId} - Get Reveal Details")
    class GetReveal {

        @Test
        @DisplayName("Should return reveal details for existing session")
        void shouldReturnRevealDetails() {
            // Given
            Session session = createTestSession();

            // When/Then
            webTestClient.get()
                    .uri("/api/reveals/{sessionId}", session.sessionId())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(SessionResponse.class)
                    .value(response -> {
                        assertThat(response.sessionId()).isEqualTo(session.sessionId());
                        assertThat(response.status().getValue()).isEqualTo("waiting");
                    });
        }

        @Test
        @DisplayName("Should return 404 for non-existent session")
        void shouldReturn404ForNonExistentSession() {
            // When/Then
            webTestClient.get()
                    .uri("/api/reveals/{sessionId}", "non-existent-session")
                    .exchange()
                    .expectStatus().isNotFound();
        }
    }

    @Nested
    @DisplayName("GET /api/session/{sessionId}/state - Get Session State (Reconnection)")
    class GetSessionState {

        @Test
        @DisplayName("Should return session state with vote counts")
        void shouldReturnSessionState() {
            // Given
            Session session = createTestSession();
            sessionService.activateSession(session.sessionId());
            String visitorId = UUID.randomUUID().toString();

            // When/Then
            webTestClient.get()
                    .uri("/api/session/{sessionId}/state?visitorId={visitorId}",
                            session.sessionId(), visitorId)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(SessionStateResponse.class)
                    .value(response -> {
                        assertThat(response.sessionId()).isEqualTo(session.sessionId());
                        assertThat(response.status().getValue()).isEqualTo("live");
                        assertThat(response.votes()).isNotNull();
                        assertThat(response.hasVoted()).isFalse();
                    });
        }

        @Test
        @DisplayName("Should return 404 for non-existent session")
        void shouldReturn404ForNonExistentSession() {
            // When/Then
            webTestClient.get()
                    .uri("/api/session/{sessionId}/state?visitorId={visitorId}",
                            "non-existent", UUID.randomUUID().toString())
                    .exchange()
                    .expectStatus().isNotFound();
        }

        @Test
        @DisplayName("Should work without visitor ID")
        void shouldWorkWithoutVisitorId() {
            // Given
            Session session = createTestSession();
            sessionService.activateSession(session.sessionId());

            // When/Then
            webTestClient.get()
                    .uri("/api/session/{sessionId}/state", session.sessionId())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(SessionStateResponse.class)
                    .value(response -> assertThat(response.sessionId()).isEqualTo(session.sessionId()));
        }

        @Test
        @DisplayName("Should return ended session with revealed gender")
        void shouldReturnEndedSessionWithGender() {
            // Given
            Session session = createTestSession();
            sessionService.activateSession(session.sessionId());
            sessionService.endSession(session.sessionId());

            // When/Then
            webTestClient.get()
                    .uri("/api/session/{sessionId}/state?visitorId={visitorId}",
                            session.sessionId(), UUID.randomUUID().toString())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(SessionStateResponse.class)
                    .value(response -> {
                        assertThat(response.status().getValue()).isEqualTo("ended");
                        assertThat(response.revealedGender().getValue()).isEqualTo("boy");
                    });
        }
    }

    @Nested
    @DisplayName("Edge Case - Invalid Session ID Format")
    class InvalidSessionIdFormat {

        @Test
        @DisplayName("Should handle special characters in session ID")
        void shouldHandleSpecialCharacters() {
            // When/Then
            webTestClient.get()
                    .uri("/api/reveals/{sessionId}", "invalid<script>")
                    .exchange()
                    .expectStatus().isNotFound();
        }
    }

    private Session createTestSession() {
        SessionCreateRequest request = new SessionCreateRequest(
                "owner-123",
                "boy",
                Instant.now().plusSeconds(3600)
        );
        return sessionService.createSession(request);
    }
}
