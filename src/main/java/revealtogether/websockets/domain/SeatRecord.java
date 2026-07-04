package revealtogether.websockets.domain;

/**
 * A participation seat on a tiered reveal.
 *
 * - countsAsSeat = true: this device claimed a fresh seat (counted by SCARD).
 * - countsAsSeat = false: this device was email-merged onto an existing seat
 *   (allowed to participate, not counted).
 * - emailHash: SHA-256 of the lowercased/trimmed email, or null. Raw guest
 *   emails are never persisted on this side.
 */
public record SeatRecord(
        String visitorId,
        String emailHash,
        boolean countsAsSeat
) {}
