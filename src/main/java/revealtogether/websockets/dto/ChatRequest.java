package revealtogether.websockets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 50, message = "Name must be at most 50 characters")
        String name,

        @NotBlank(message = "Message is required")
        @Size(max = 280, message = "Message must be at most 280 characters")
        String message,

        @NotBlank(message = "Visitor ID is required")
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "Invalid visitor ID format")
        String visitorId
) {}
