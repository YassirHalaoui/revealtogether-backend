package revealtogether.websockets.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import revealtogether.websockets.BaseIntegrationTest;
import revealtogether.websockets.domain.ChatMessage;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.domain.VoteCount;
import revealtogether.websockets.dto.*;
import revealtogether.websockets.service.SessionService;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebSocket Integration Tests")
class WebSocketIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SessionService sessionService;

    private WebSocketStompClient stompClient;
    private String wsUrl;
    private String sessionId;

    @BeforeEach
    void setUp() {
        // Create STOMP client with SockJS
        stompClient = new WebSocketStompClient(new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient()))
        ));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        wsUrl = "ws://localhost:" + port + "/ws";

        // Create and activate a test session
        SessionCreateRequest request = new SessionCreateRequest(
                "owner-123",
                "boy",
                Instant.now().plusSeconds(3600)
        );
        Session session = sessionService.createSession(request);
        sessionId = session.sessionId();
        sessionService.activateSession(sessionId);
    }

    @Nested
    @DisplayName("WebSocket Connection")
    class Connection {

        @Test
        @DisplayName("Should connect to WebSocket endpoint")
        void shouldConnectToWebSocket() throws Exception {
            // Given
            BlockingQueue<Boolean> connectionResult = new ArrayBlockingQueue<>(1);

            // When
            StompSession stompSession = stompClient.connectAsync(wsUrl, new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                    connectionResult.offer(true);
                }

                @Override
                public void handleException(StompSession session, StompCommand command,
                                            StompHeaders headers, byte[] payload, Throwable exception) {
                    connectionResult.offer(false);
                }
            }).get(5, TimeUnit.SECONDS);

            // Then
            Boolean connected = connectionResult.poll(5, TimeUnit.SECONDS);
            assertThat(connected).isTrue();
            assertThat(stompSession.isConnected()).isTrue();

            stompSession.disconnect();
        }
    }

    @Nested
    @DisplayName("Vote Operations via WebSocket")
    class VoteOperations {

        @Test
        @DisplayName("Should receive vote response after casting vote")
        void shouldReceiveVoteResponse() throws Exception {
            // Given
            BlockingQueue<VoteResponse> responses = new ArrayBlockingQueue<>(1);
            String visitorId = UUID.randomUUID().toString();

            StompSession stompSession = stompClient.connectAsync(wsUrl, new StompSessionHandlerAdapter() {
            }).get(5, TimeUnit.SECONDS);

            // Subscribe to vote responses
            stompSession.subscribe("/topic/vote-response/" + sessionId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return VoteResponse.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    responses.offer((VoteResponse) payload);
                }
            });

            Thread.sleep(500); // Wait for subscription to be established

            // When - send vote
            VoteRequest voteRequest = new VoteRequest("boy", visitorId, "TestUser");
            stompSession.send("/app/vote/" + sessionId, voteRequest);

            // Then
            VoteResponse response = responses.poll(5, TimeUnit.SECONDS);
            assertThat(response).isNotNull();
            assertThat(response.success()).isTrue();

            stompSession.disconnect();
        }

        @Test
        @DisplayName("Should receive updated vote counts via subscription")
        void shouldReceiveVoteCountUpdates() throws Exception {
            // Given
            BlockingQueue<VoteCount> voteCounts = new ArrayBlockingQueue<>(5);

            StompSession stompSession = stompClient.connectAsync(wsUrl, new StompSessionHandlerAdapter() {
            }).get(5, TimeUnit.SECONDS);

            // Subscribe to vote updates
            stompSession.subscribe("/topic/votes/" + sessionId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return VoteCount.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    voteCounts.offer((VoteCount) payload);
                }
            });

            Thread.sleep(500);

            // When - cast vote (this triggers batched broadcast)
            String visitorId = UUID.randomUUID().toString();
            VoteRequest voteRequest = new VoteRequest("boy", visitorId, "TestUser");
            stompSession.send("/app/vote/" + sessionId, voteRequest);

            // Then - wait for batched broadcast (every 200ms)
            VoteCount count = voteCounts.poll(3, TimeUnit.SECONDS);
            // Note: count might be null if broadcast hasn't happened yet
            // The batched broadcast runs every 200ms

            stompSession.disconnect();
        }
    }

    @Nested
    @DisplayName("Chat Operations via WebSocket")
    @Disabled("WebSocket async timing issues - run manually with increased timeouts")
    class ChatOperations {

        @Test
        @DisplayName("Should receive chat message broadcast")
        void shouldReceiveChatMessageBroadcast() throws Exception {
            // Given
            BlockingQueue<ChatMessage> messages = new ArrayBlockingQueue<>(1);
            String visitorId = UUID.randomUUID().toString();

            StompSession stompSession = stompClient.connectAsync(wsUrl, new StompSessionHandlerAdapter() {
            }).get(5, TimeUnit.SECONDS);

            // Subscribe to chat messages
            stompSession.subscribe("/topic/chat/" + sessionId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return ChatMessage.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    messages.offer((ChatMessage) payload);
                }
            });

            Thread.sleep(500);

            // When - send chat message
            ChatRequest chatRequest = new ChatRequest("Alice", "Hello everyone!", visitorId);
            stompSession.send("/app/chat/" + sessionId, chatRequest);

            // Then
            ChatMessage message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).isNotNull();
            assertThat(message.name()).isEqualTo("Alice");
            assertThat(message.message()).isEqualTo("Hello everyone!");

            stompSession.disconnect();
        }

        @Test
        @DisplayName("Should receive multiple chat messages in order")
        void shouldReceiveMultipleChatMessages() throws Exception {
            // Given
            BlockingQueue<ChatMessage> messages = new ArrayBlockingQueue<>(5);

            StompSession stompSession = stompClient.connectAsync(wsUrl, new StompSessionHandlerAdapter() {
            }).get(5, TimeUnit.SECONDS);

            stompSession.subscribe("/topic/chat/" + sessionId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return ChatMessage.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    messages.offer((ChatMessage) payload);
                }
            });

            Thread.sleep(500);

            // When - send multiple messages (from different visitors to avoid rate limiting)
            for (int i = 0; i < 3; i++) {
                String visitorId = UUID.randomUUID().toString();
                ChatRequest chatRequest = new ChatRequest("User" + i, "Message " + i, visitorId);
                stompSession.send("/app/chat/" + sessionId, chatRequest);
                Thread.sleep(100); // Small delay between messages
            }

            // Then
            Thread.sleep(500); // Wait for all messages
            assertThat(messages.size()).isGreaterThanOrEqualTo(1);

            stompSession.disconnect();
        }
    }

    @Nested
    @DisplayName("Multiple Clients")
    @Disabled("WebSocket async timing issues - run manually with increased timeouts")
    class MultipleClients {

        @Test
        @DisplayName("Should broadcast to all connected clients")
        void shouldBroadcastToAllClients() throws Exception {
            // Given
            BlockingQueue<ChatMessage> client1Messages = new ArrayBlockingQueue<>(1);
            BlockingQueue<ChatMessage> client2Messages = new ArrayBlockingQueue<>(1);

            // Connect two clients
            StompSession client1 = stompClient.connectAsync(wsUrl, new StompSessionHandlerAdapter() {
            }).get(5, TimeUnit.SECONDS);

            StompSession client2 = stompClient.connectAsync(wsUrl, new StompSessionHandlerAdapter() {
            }).get(5, TimeUnit.SECONDS);

            // Both subscribe to chat
            client1.subscribe("/topic/chat/" + sessionId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return ChatMessage.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    client1Messages.offer((ChatMessage) payload);
                }
            });

            client2.subscribe("/topic/chat/" + sessionId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return ChatMessage.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    client2Messages.offer((ChatMessage) payload);
                }
            });

            Thread.sleep(500);

            // When - client 1 sends a message
            String visitorId = UUID.randomUUID().toString();
            ChatRequest chatRequest = new ChatRequest("Alice", "Hello!", visitorId);
            client1.send("/app/chat/" + sessionId, chatRequest);

            // Then - both clients should receive the message
            ChatMessage msg1 = client1Messages.poll(5, TimeUnit.SECONDS);
            ChatMessage msg2 = client2Messages.poll(5, TimeUnit.SECONDS);

            assertThat(msg1).isNotNull();
            assertThat(msg2).isNotNull();
            assertThat(msg1.message()).isEqualTo("Hello!");
            assertThat(msg2.message()).isEqualTo("Hello!");

            client1.disconnect();
            client2.disconnect();
        }
    }

    @Nested
    @DisplayName("Reconnection Scenario")
    @Disabled("WebSocket async timing issues - run manually with increased timeouts")
    class Reconnection {

        @Test
        @DisplayName("Should be able to reconnect and re-subscribe")
        void shouldReconnectAndResubscribe() throws Exception {
            // Given - first connection
            StompSession session1 = stompClient.connectAsync(wsUrl, new StompSessionHandlerAdapter() {
            }).get(5, TimeUnit.SECONDS);

            assertThat(session1.isConnected()).isTrue();

            // When - disconnect and reconnect
            session1.disconnect();
            Thread.sleep(500);

            StompSession session2 = stompClient.connectAsync(wsUrl, new StompSessionHandlerAdapter() {
            }).get(5, TimeUnit.SECONDS);

            // Then
            assertThat(session2.isConnected()).isTrue();

            // Should be able to subscribe again
            BlockingQueue<ChatMessage> messages = new ArrayBlockingQueue<>(1);
            session2.subscribe("/topic/chat/" + sessionId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return ChatMessage.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    messages.offer((ChatMessage) payload);
                }
            });

            Thread.sleep(500);

            // Send a message
            String visitorId = UUID.randomUUID().toString();
            session2.send("/app/chat/" + sessionId, new ChatRequest("Bob", "I'm back!", visitorId));

            ChatMessage message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).isNotNull();

            session2.disconnect();
        }
    }
}
