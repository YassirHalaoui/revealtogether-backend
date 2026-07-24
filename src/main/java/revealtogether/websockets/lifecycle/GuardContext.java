package revealtogether.websockets.lifecycle;

import java.util.Map;

/**
 * WP3 — the condition fields guards evaluate.
 *
 * Built from the reveal document, never from client input: a client cannot
 * assert "my entitlement is active" and walk through a guard.
 */
public record GuardContext(
        boolean secretSealed,
        boolean entitlementActive,
        boolean suspended,
        boolean requiredEventFields,
        boolean requiredPollFields,
        boolean seatGatingSatisfied,
        boolean preflightPassed
) {
    public static GuardContext fromRevealDoc(Map<String, Object> doc) {
        if (doc == null) return none();
        boolean sealed = Boolean.TRUE.equals(doc.get("secretSealed")) || doc.get("gender") != null;
        // Legacy reveals predate tiering and are entitled by grandfather rule.
        String payment = str(doc.get("paymentStatus"));
        boolean entitled = "completed".equals(payment)
                || "legacy".equals(str(doc.get("tier")))
                || (payment == null && doc.get("tier") == null);
        boolean names = str(doc.get("motherName")) != null || str(doc.get("fatherName")) != null;
        boolean scheduled = doc.get("revealTime") != null;
        return new GuardContext(
                sealed,
                entitled,
                Boolean.TRUE.equals(doc.get("suspended")),
                names && scheduled,
                names,
                true,   // seat gating is evaluated by SeatService at upgrade time
                true    // preflight is evaluated by ArmReveal at commit time
        );
    }

    public static GuardContext none() {
        return new GuardContext(false, false, false, false, false, false, false);
    }

    /** Builder-ish helpers keep tests readable. */
    public GuardContext withSecretSealed(boolean v) {
        return new GuardContext(v, entitlementActive, suspended, requiredEventFields, requiredPollFields, seatGatingSatisfied, preflightPassed);
    }

    public GuardContext withEntitlementActive(boolean v) {
        return new GuardContext(secretSealed, v, suspended, requiredEventFields, requiredPollFields, seatGatingSatisfied, preflightPassed);
    }

    public GuardContext withPreflightPassed(boolean v) {
        return new GuardContext(secretSealed, entitlementActive, suspended, requiredEventFields, requiredPollFields, seatGatingSatisfied, v);
    }

    public GuardContext withSeatGatingSatisfied(boolean v) {
        return new GuardContext(secretSealed, entitlementActive, suspended, requiredEventFields, requiredPollFields, v, preflightPassed);
    }

    public static GuardContext allSatisfied() {
        return new GuardContext(true, true, false, true, true, true, true);
    }

    private static String str(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }
}
