package revealtogether.websockets.dto;

public record VoteResponse(
        boolean success,
        String message
) {
    public static VoteResponse ok() {
        return new VoteResponse(true, "Vote recorded");
    }

    public static VoteResponse alreadyVoted() {
        return new VoteResponse(false, "Already voted");
    }

    public static VoteResponse sessionEnded() {
        return new VoteResponse(false, "Session has ended");
    }

    public static VoteResponse rateLimited() {
        return new VoteResponse(false, "Rate limited, try again later");
    }
}
