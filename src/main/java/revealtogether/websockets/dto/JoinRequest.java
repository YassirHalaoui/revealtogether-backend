package revealtogether.websockets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record JoinRequest(
        @NotBlank(message = "Visitor ID is required")
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "Invalid visitor ID format")
        String visitorId,

        @Size(max = 254, message = "Email too long")
        String email,

        String idToken
) {}
