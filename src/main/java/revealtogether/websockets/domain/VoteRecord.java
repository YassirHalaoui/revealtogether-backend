package revealtogether.websockets.domain;

import java.time.Instant;

public record VoteRecord(
        String visitorId,
        String name,
        VoteOption option,
        Instant timestamp
) {
    public static VoteRecord create(String visitorId, String name, VoteOption option) {
        return new VoteRecord(visitorId, name, option, Instant.now());
    }
}
