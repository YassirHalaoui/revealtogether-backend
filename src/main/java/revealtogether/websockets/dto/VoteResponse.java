package revealtogether.websockets.dto;

/**
 * Vote acknowledgement, broadcast on /topic/vote-response/{sessionId}.
 *
 * The topic is PUBLIC (SendTo, not SendToUser): every subscriber receives every
 * voter's response. Clients MUST match visitorId against their own before
 * reacting — treating any success/rejection frame as your own is a bug.
 */
public record VoteResponse(
        boolean success,
        String message,
        String visitorId
) {
    public static VoteResponse ok(String visitorId) {
        return new VoteResponse(true, "Vote recorded", visitorId);
    }

    public static VoteResponse alreadyVoted(String visitorId) {
        return new VoteResponse(false, "Already voted", visitorId);
    }

    public static VoteResponse sessionEnded(String visitorId) {
        return new VoteResponse(false, "Session has ended", visitorId);
    }

    public static VoteResponse rateLimited(String visitorId) {
        return new VoteResponse(false, "Rate limited, try again later", visitorId);
    }

    public static VoteResponse atCapacity(String visitorId) {
        return new VoteResponse(false, "At capacity", visitorId);
    }

    public static VoteResponse sessionNotFound(String visitorId) {
        return new VoteResponse(false, "Session not found", visitorId);
    }
}
