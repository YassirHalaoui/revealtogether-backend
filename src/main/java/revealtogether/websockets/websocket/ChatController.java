package revealtogether.websockets.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import revealtogether.websockets.dto.ChatRequest;
import revealtogether.websockets.service.ChatService;

@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @MessageMapping("/chat/{sessionId}")
    public void chat(
            @DestinationVariable String sessionId,
            ChatRequest request
    ) {
        log.debug("Chat received: session={}, from={}", sessionId, request.name());
        chatService.sendMessage(sessionId, request);
    }
}
