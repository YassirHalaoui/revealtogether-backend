package revealtogether.websockets.dto;

public record PublicLinkResponse(
        String revealId,
        String publicToken,
        String publicPath,
        int tokenVersion
) {
    public static PublicLinkResponse of(String revealId, String token, int version) {
        return new PublicLinkResponse(revealId, token, "/reveal/" + token, version);
    }
}
