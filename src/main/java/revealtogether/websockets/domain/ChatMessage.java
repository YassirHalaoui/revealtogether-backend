package revealtogether.websockets.domain;

import java.time.Instant;

public record ChatMessage(
        String name,
        String message,
        String visitorId,
        Instant timestamp
) {
    public static ChatMessage of(String name, String message, String visitorId) {
        return new ChatMessage(name, message, visitorId, Instant.now());
    }
}
