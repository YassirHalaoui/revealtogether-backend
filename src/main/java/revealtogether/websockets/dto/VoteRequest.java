package revealtogether.websockets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VoteRequest(
        @NotBlank(message = "Vote option is required")
        @Pattern(regexp = "^(boy|girl)$", message = "Option must be 'boy' or 'girl'")
        String option,

        @NotBlank(message = "Visitor ID is required")
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "Invalid visitor ID format")
        String visitorId,

        @Size(max = 50, message = "Name must be 50 characters or less")
        String name  // Optional: for floating vote display
) {
    // Compact constructor to handle null name
    public VoteRequest {
        if (name == null || name.isBlank()) {
            name = "Guest";
        }
    }
}
