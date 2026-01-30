package revealtogether.websockets.domain;

public record VoteCount(
        long boy,
        long girl
) {
    public static VoteCount empty() {
        return new VoteCount(0, 0);
    }

    public long total() {
        return boy + girl;
    }
}
