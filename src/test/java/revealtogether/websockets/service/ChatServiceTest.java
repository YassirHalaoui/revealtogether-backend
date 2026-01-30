package revealtogether.websockets.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import revealtogether.websockets.BaseIntegrationTest;
import revealtogether.websockets.domain.ChatMessage;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.dto.ChatRequest;
import revealtogether.websockets.dto.SessionCreateRequest;
import revealtogether.websockets.repository.RedisRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChatService Integration Tests")
class ChatServiceTest extends BaseIntegrationTest {

    @Autowired
    private ChatService chatService;

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

        // Activate the session
        sessionService.activateSession(sessionId);
    }

    @Nested
    @DisplayName("Nominal Flow - Send Message")
    class NominalFlow {

        @Test
        @DisplayName("Should successfully send chat message")
        void shouldSendChatMessage() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            ChatRequest request = new ChatRequest("Alice", "Hello everyone!", visitorId);

            // When
            boolean sent = chatService.sendMessage(sessionId, request);

            // Then
            assertThat(sent).isTrue();

            List<ChatMessage> messages = chatService.getRecentMessages(sessionId, 50);
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).name()).isEqualTo("Alice");
            assertThat(messages.get(0).message()).isEqualTo("Hello everyone!");
        }

        @Test
        @DisplayName("Should preserve message order")
        void shouldPreserveMessageOrder() throws InterruptedException {
            // Given
            String[] names = {"Alice", "Bob", "Charlie"};
            String[] messages = {"First", "Second", "Third"};

            // When
            for (int i = 0; i < names.length; i++) {
                String visitorId = UUID.randomUUID().toString();
                ChatRequest request = new ChatRequest(names[i], messages[i], visitorId);
                chatService.sendMessage(sessionId, request);
                Thread.sleep(10); // Small delay to ensure ordering
            }

            // Then
            List<ChatMessage> retrieved = chatService.getRecentMessages(sessionId, 50);
            assertThat(retrieved).hasSize(3);
            assertThat(retrieved.get(0).name()).isEqualTo("Alice");
            assertThat(retrieved.get(1).name()).isEqualTo("Bob");
            assertThat(retrieved.get(2).name()).isEqualTo("Charlie");
        }
    }

    @Nested
    @DisplayName("Edge Case - Rate Limiting (Chat Spam Prevention)")
    class RateLimiting {

        @Test
        @DisplayName("Should rate limit rapid messages from same visitor")
        void shouldRateLimitRapidMessages() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            ChatRequest request1 = new ChatRequest("Alice", "First message", visitorId);
            ChatRequest request2 = new ChatRequest("Alice", "Second message", visitorId);

            // When
            boolean first = chatService.sendMessage(sessionId, request1);
            boolean second = chatService.sendMessage(sessionId, request2);

            // Then
            assertThat(first).isTrue();
            assertThat(second).isFalse(); // Rate limited

            List<ChatMessage> messages = chatService.getRecentMessages(sessionId, 50);
            assertThat(messages).hasSize(1);
        }

        @Test
        @DisplayName("Should allow messages from different visitors")
        void shouldAllowDifferentVisitors() {
            // Given
            ChatRequest request1 = new ChatRequest("Alice", "Hello", UUID.randomUUID().toString());
            ChatRequest request2 = new ChatRequest("Bob", "Hi there", UUID.randomUUID().toString());

            // When
            boolean first = chatService.sendMessage(sessionId, request1);
            boolean second = chatService.sendMessage(sessionId, request2);

            // Then
            assertThat(first).isTrue();
            assertThat(second).isTrue();

            List<ChatMessage> messages = chatService.getRecentMessages(sessionId, 50);
            assertThat(messages).hasSize(2);
        }

        @Test
        @DisplayName("Should allow message after rate limit expires")
        void shouldAllowAfterRateLimitExpires() throws InterruptedException {
            // Given
            String visitorId = UUID.randomUUID().toString();
            ChatRequest request1 = new ChatRequest("Alice", "First", visitorId);
            ChatRequest request2 = new ChatRequest("Alice", "Second", visitorId);

            // When
            chatService.sendMessage(sessionId, request1);
            Thread.sleep(1100); // Wait for rate limit to expire
            boolean second = chatService.sendMessage(sessionId, request2);

            // Then
            assertThat(second).isTrue();

            List<ChatMessage> messages = chatService.getRecentMessages(sessionId, 50);
            assertThat(messages).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Edge Case - Message Validation")
    class MessageValidation {

        @Test
        @DisplayName("Should sanitize HTML in message (XSS prevention)")
        void shouldSanitizeHtmlInMessage() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            ChatRequest request = new ChatRequest(
                    "Alice",
                    "<script>alert('xss')</script>Hello",
                    visitorId
            );

            // When
            chatService.sendMessage(sessionId, request);

            // Then
            List<ChatMessage> messages = chatService.getRecentMessages(sessionId, 50);
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).message()).doesNotContain("<script>");
            assertThat(messages.get(0).message()).contains("&lt;script&gt;");
        }

        @Test
        @DisplayName("Should sanitize HTML in name")
        void shouldSanitizeHtmlInName() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            ChatRequest request = new ChatRequest(
                    "<b>Evil</b>",
                    "Hello",
                    visitorId
            );

            // When
            chatService.sendMessage(sessionId, request);

            // Then
            List<ChatMessage> messages = chatService.getRecentMessages(sessionId, 50);
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).name()).doesNotContain("<b>");
        }

        @Test
        @DisplayName("Should truncate message exceeding max length")
        void shouldTruncateLongMessage() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            String longMessage = "A".repeat(500); // Exceeds 280 char limit
            ChatRequest request = new ChatRequest("Alice", longMessage, visitorId);

            // When
            chatService.sendMessage(sessionId, request);

            // Then
            List<ChatMessage> messages = chatService.getRecentMessages(sessionId, 50);
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).message().length()).isLessThanOrEqualTo(280);
        }

        @Test
        @DisplayName("Should truncate name exceeding max length")
        void shouldTruncateLongName() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            String longName = "A".repeat(100); // Exceeds 50 char limit
            ChatRequest request = new ChatRequest(longName, "Hello", visitorId);

            // When
            chatService.sendMessage(sessionId, request);

            // Then
            List<ChatMessage> messages = chatService.getRecentMessages(sessionId, 50);
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).name().length()).isLessThanOrEqualTo(50);
        }

        @Test
        @DisplayName("Should reject empty message")
        void shouldRejectEmptyMessage() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            ChatRequest request = new ChatRequest("Alice", "   ", visitorId);

            // When
            boolean sent = chatService.sendMessage(sessionId, request);

            // Then
            assertThat(sent).isFalse();

            List<ChatMessage> messages = chatService.getRecentMessages(sessionId, 50);
            assertThat(messages).isEmpty();
        }
    }

    @Nested
    @DisplayName("Edge Case - Session State Validation")
    class SessionStateValidation {

        @Test
        @DisplayName("Should reject message on non-existent session")
        void shouldRejectMessageOnNonExistentSession() {
            // Given
            String visitorId = UUID.randomUUID().toString();
            ChatRequest request = new ChatRequest("Alice", "Hello", visitorId);

            // When
            boolean sent = chatService.sendMessage("non-existent-session", request);

            // Then
            assertThat(sent).isFalse();
        }

        @Test
        @DisplayName("Should reject message on ended session")
        void shouldRejectMessageOnEndedSession() {
            // Given
            sessionService.endSession(sessionId);
            String visitorId = UUID.randomUUID().toString();
            ChatRequest request = new ChatRequest("Alice", "Hello", visitorId);

            // When
            boolean sent = chatService.sendMessage(sessionId, request);

            // Then
            assertThat(sent).isFalse();
        }
    }

    @Nested
    @DisplayName("Edge Case - High Volume Chat")
    class HighVolumeChat {

        @Test
        @DisplayName("Should handle many messages without data loss")
        void shouldHandleManyMessages() {
            // Given
            int numMessages = 50;

            // When
            for (int i = 0; i < numMessages; i++) {
                String visitorId = UUID.randomUUID().toString();
                ChatRequest request = new ChatRequest(
                        "User" + i,
                        "Message " + i,
                        visitorId
                );
                chatService.sendMessage(sessionId, request);
            }

            // Then
            List<ChatMessage> messages = chatService.getAllMessages(sessionId);
            assertThat(messages).hasSize(numMessages);
        }

        @Test
        @DisplayName("Should handle concurrent messages correctly")
        void shouldHandleConcurrentMessages() throws InterruptedException {
            // Given
            int numMessages = 30;
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(numMessages);
            AtomicInteger successCount = new AtomicInteger(0);

            // When
            for (int i = 0; i < numMessages; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        String visitorId = UUID.randomUUID().toString();
                        ChatRequest request = new ChatRequest(
                                "User" + index,
                                "Message " + index,
                                visitorId
                        );
                        if (chatService.sendMessage(sessionId, request)) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            // Then
            List<ChatMessage> messages = chatService.getAllMessages(sessionId);
            assertThat(messages.size()).isEqualTo(successCount.get());
        }
    }
}
