package revealtogether.websockets.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import revealtogether.websockets.domain.ChatMessage;
import revealtogether.websockets.domain.SessionStatus;
import revealtogether.websockets.dto.ChatRequest;
import revealtogether.websockets.repository.RedisRepository;

import java.util.List;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final RedisRepository redisRepository;
    private final SessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final int maxMessageLength;
    private final int maxNameLength;

    public ChatService(
            RedisRepository redisRepository,
            SessionService sessionService,
            SimpMessagingTemplate messagingTemplate,
            @Value("${app.chat.max-length:280}") int maxMessageLength,
            @Value("${app.name.max-length:50}") int maxNameLength
    ) {
        this.redisRepository = redisRepository;
        this.sessionService = sessionService;
        this.messagingTemplate = messagingTemplate;
        this.maxMessageLength = maxMessageLength;
        this.maxNameLength = maxNameLength;
    }

    public boolean sendMessage(String sessionId, ChatRequest request) {
        // Check rate limit
        if (redisRepository.isRateLimited(request.visitorId())) {
            log.debug("Chat rate limited for visitor: {}", request.visitorId());
            return false;
        }

        // Check session exists and is active
        var sessionOpt = sessionService.getSession(sessionId);
        if (sessionOpt.isEmpty()) {
            log.warn("Chat attempted on non-existent session: {}", sessionId);
            return false;
        }

        var session = sessionOpt.get();
        if (session.status() == SessionStatus.ENDED) {
            log.debug("Chat attempted on ended session: {}", sessionId);
            return false;
        }

        // Sanitize and validate input
        String sanitizedName = sanitize(request.name(), maxNameLength);
        String sanitizedMessage = sanitize(request.message(), maxMessageLength);

        if (sanitizedMessage.isBlank()) {
            return false;
        }

        // Create and store message
        ChatMessage chatMessage = ChatMessage.of(sanitizedName, sanitizedMessage, request.visitorId());
        redisRepository.addChatMessage(sessionId, chatMessage);

        // Broadcast immediately
        messagingTemplate.convertAndSend("/topic/chat/" + sessionId, chatMessage);

        log.debug("Chat message sent: session={}, from={}", sessionId, sanitizedName);
        return true;
    }

    public List<ChatMessage> getRecentMessages(String sessionId, int limit) {
        return redisRepository.getRecentMessages(sessionId, limit);
    }

    public List<ChatMessage> getAllMessages(String sessionId) {
        return redisRepository.getAllMessages(sessionId);
    }

    private String sanitize(String input, int maxLength) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim();
        if (trimmed.length() > maxLength) {
            trimmed = trimmed.substring(0, maxLength);
        }
        return HtmlUtils.htmlEscape(trimmed);
    }
}
