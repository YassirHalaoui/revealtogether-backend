package revealtogether.websockets.dto;

import revealtogether.websockets.domain.VoteCount;
import revealtogether.websockets.domain.VoteOption;

public record RevealEvent(
        String type,
        VoteOption gender,
        VoteCount finalVotes
) {
    public static RevealEvent of(VoteOption gender, VoteCount votes) {
        return new RevealEvent("reveal", gender, votes);
    }
}
