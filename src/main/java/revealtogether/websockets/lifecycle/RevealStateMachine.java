package revealtogether.websockets.lifecycle;

import java.util.List;
import java.util.function.Predicate;

import static revealtogether.websockets.lifecycle.RevealCommand.*;
import static revealtogether.websockets.lifecycle.RevealState.*;

/**
 * WP3 — the transition table. Pure and side-effect free so every allowed AND
 * forbidden transition can be tested without infrastructure; persistence lives
 * in RevealLifecycleService.
 *
 * Anything not in this table is rejected with INVALID_TRANSITION. In
 * particular GUESS_OPEN has no path to LOBBY_OPEN / LOCKED / REVEAL_COMMITTED:
 * a free party must be upgraded first, by construction rather than by check.
 */
public final class RevealStateMachine {

    /** A hard requirement. Failing it rejects the command with this code. */
    public enum Guard {
        ENTITLEMENT(GuardContext::entitlementActive, "ENTITLEMENT_REQUIRED"),
        SECRET(GuardContext::secretSealed, "SECRET_NOT_SEALED"),
        EVENT_FIELDS(GuardContext::requiredEventFields, "INVALID_TRANSITION"),
        POLL_FIELDS(GuardContext::requiredPollFields, "INVALID_TRANSITION"),
        SEAT_GATING(GuardContext::seatGatingSatisfied, "SEAT_GATING"),
        PREFLIGHT(GuardContext::preflightPassed, "PREFLIGHT_FAILED");

        private final Predicate<GuardContext> test;
        private final String code;

        Guard(Predicate<GuardContext> test, String code) {
            this.test = test;
            this.code = code;
        }

        public boolean passes(GuardContext ctx) { return test.test(ctx); }
        public String code() { return code; }
    }

    /**
     * @param selector when false, this row does not apply and the next row for
     *                 the same (from, command) is tried. Used where one command
     *                 has two legitimate targets — e.g. SubmitForReveal lands in
     *                 READY when the secret is already sealed, AWAITING_SECRET
     *                 otherwise.
     */
    private record Row(RevealState from, RevealCommand command, RevealState to,
                       List<Guard> guards, Predicate<GuardContext> selector) {}

    private static final Predicate<GuardContext> ALWAYS = ctx -> true;

    // Order matters: the first applicable row wins.
    private static final List<Row> TABLE = List.of(
            new Row(DRAFT, PublishGuessParty, GUESS_OPEN, List.of(Guard.POLL_FIELDS), ALWAYS),
            // Secret already sealed and entitled → straight to READY.
            new Row(DRAFT, SubmitForReveal, READY,
                    List.of(Guard.EVENT_FIELDS), ctx -> ctx.secretSealed() && ctx.entitlementActive()),
            new Row(DRAFT, SubmitForReveal, AWAITING_SECRET, List.of(Guard.EVENT_FIELDS), ALWAYS),

            new Row(GUESS_OPEN, UpgradeGuessParty, READY,
                    List.of(Guard.ENTITLEMENT, Guard.SEAT_GATING), GuardContext::secretSealed),
            new Row(GUESS_OPEN, UpgradeGuessParty, AWAITING_SECRET,
                    List.of(Guard.ENTITLEMENT, Guard.SEAT_GATING), ALWAYS),

            new Row(AWAITING_SECRET, SealSecret, READY, List.of(Guard.SECRET), ALWAYS),
            new Row(READY, PublishReveal, PUBLISHED, List.of(Guard.ENTITLEMENT), ALWAYS),
            new Row(PUBLISHED, OpenLobby, LOBBY_OPEN, List.of(), ALWAYS),
            new Row(LOBBY_OPEN, LockVoting, LOCKED, List.of(), ALWAYS),
            new Row(LOBBY_OPEN, ArmReveal, LOCKED, List.of(Guard.PREFLIGHT, Guard.SECRET), ALWAYS),
            new Row(LOCKED, CommitReveal, REVEAL_COMMITTED, List.of(Guard.SECRET), ALWAYS),
            new Row(LOCKED, ArmReveal, REVEAL_COMMITTED, List.of(Guard.PREFLIGHT, Guard.SECRET), ALWAYS),
            new Row(REVEAL_COMMITTED, ReleaseOutcome, REVEALED, List.of(Guard.SECRET), ALWAYS),
            new Row(REVEALED, EndReveal, ENDED, List.of(), ALWAYS),
            new Row(ENDED, ArchiveReveal, ARCHIVED, List.of(), ALWAYS),

            // Cancellation: allowed everywhere pre-commitment, nowhere after.
            new Row(DRAFT, CancelReveal, CANCELLED, List.of(), ALWAYS),
            new Row(GUESS_OPEN, CancelReveal, CANCELLED, List.of(), ALWAYS),
            new Row(AWAITING_SECRET, CancelReveal, CANCELLED, List.of(), ALWAYS),
            new Row(READY, CancelReveal, CANCELLED, List.of(), ALWAYS),
            new Row(PUBLISHED, CancelReveal, CANCELLED, List.of(), ALWAYS),
            new Row(LOBBY_OPEN, CancelReveal, CANCELLED, List.of(), ALWAYS),
            new Row(LOCKED, CancelReveal, CANCELLED, List.of(), ALWAYS)
    );

    public sealed interface Result permits Allowed, Rejected {}

    public record Allowed(RevealState to) implements Result {}

    public record Rejected(String code, String message) implements Result {}

    private RevealStateMachine() {}

    public static Result evaluate(RevealState from, RevealCommand command, GuardContext ctx) {
        if (from == null) {
            return new Rejected("INVALID_TRANSITION", "Reveal has no lifecycle state");
        }
        if (ctx.suspended()) {
            return new Rejected("SUSPENDED", "This reveal is suspended");
        }

        List<Row> candidates = TABLE.stream()
                .filter(r -> r.from() == from && r.command() == command)
                .toList();

        if (candidates.isEmpty()) {
            return new Rejected("INVALID_TRANSITION",
                    "%s is not allowed from %s".formatted(command, from));
        }

        for (Row row : candidates) {
            if (!row.selector().test(ctx)) continue;
            for (Guard guard : row.guards()) {
                if (!guard.passes(ctx)) {
                    return new Rejected(guard.code(),
                            "%s blocked from %s: %s not satisfied".formatted(command, from, guard.name()));
                }
            }
            return new Allowed(row.to());
        }

        // Every candidate row's selector declined — the last row's guards are
        // the most informative failure to report.
        Row last = candidates.get(candidates.size() - 1);
        return new Rejected("INVALID_TRANSITION",
                "%s from %s did not match any applicable transition".formatted(command, last.from()));
    }

    /** All (from, command) pairs the table permits — used by the test sweep. */
    public static List<RevealState> targetsFor(RevealState from, RevealCommand command) {
        return TABLE.stream().filter(r -> r.from() == from && r.command() == command)
                .map(Row::to).toList();
    }
}
