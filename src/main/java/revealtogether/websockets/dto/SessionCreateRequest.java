package revealtogether.websockets.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
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
        Instant revealTime,

        String motherName,

        String fatherName,

        String theme,

        String paymentStatus,

        String existingRevealId,

        @Pattern(regexp = "^(intimate|celebration|grand)$", message = "Tier must be 'intimate', 'celebration' or 'grand'")
        String tier,

        @Min(value = 1, message = "Seat limit must be at least 1")
        Integer seatLimit
) {
    /** Legacy 8-arg constructor: requests without tier info (= uncapped, rule 3). */
    public SessionCreateRequest(String ownerId, String gender, Instant revealTime,
                                String motherName, String fatherName, String theme,
                                String paymentStatus, String existingRevealId) {
        this(ownerId, gender, revealTime, motherName, fatherName, theme,
                paymentStatus, existingRevealId, null, null);
    }
}
