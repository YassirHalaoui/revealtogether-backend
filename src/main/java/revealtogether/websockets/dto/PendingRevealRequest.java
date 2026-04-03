package revealtogether.websockets.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public record PendingRevealRequest(
        @NotNull(message = "Gender is required")
        @Pattern(regexp = "^(boy|girl)$", message = "Gender must be 'boy' or 'girl'")
        String gender,

        String motherName,
        String fatherName,
        Instant revealTime,
        String theme
) {}
