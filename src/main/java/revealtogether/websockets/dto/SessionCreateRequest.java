package revealtogether.websockets.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public record SessionCreateRequest(
        @NotBlank(message = "Owner ID is required")
        String ownerId,

        @NotNull(message = "Gender is required")
        @Pattern(regexp = "^(boy|girl)$", message = "Gender must be 'boy' or 'girl'")
        String gender,

        @NotNull(message = "Reveal time is required")
        @Future(message = "Reveal time must be in the future")
        Instant revealTime
) {}
