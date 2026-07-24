package revealtogether.websockets.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static revealtogether.websockets.lifecycle.RevealCommand.*;
import static revealtogether.websockets.lifecycle.RevealState.*;

/**
 * WP3 acceptance: "covers every allowed AND every forbidden transition, and the
 * legacy mapping". Pure unit tests — no containers, no Firestore.
 */
@DisplayName("WP3 Reveal lifecycle")
class RevealStateMachineTest {

    private static RevealState allow(RevealState from, RevealCommand cmd, GuardContext ctx) {
        var result = RevealStateMachine.evaluate(from, cmd, ctx);
        assertThat(result)
                .as("%s from %s should be allowed", cmd, from)
                .isInstanceOf(RevealStateMachine.Allowed.class);
        return ((RevealStateMachine.Allowed) result).to();
    }

    private static RevealStateMachine.Rejected reject(RevealState from, RevealCommand cmd, GuardContext ctx) {
        var result = RevealStateMachine.evaluate(from, cmd, ctx);
        assertThat(result)
                .as("%s from %s should be rejected", cmd, from)
                .isInstanceOf(RevealStateMachine.Rejected.class);
        return (RevealStateMachine.Rejected) result;
    }

    // ---------- The happy paths ----------

    @Test
    @DisplayName("Paid path: DRAFT → AWAITING_SECRET → READY → PUBLISHED → LOBBY_OPEN → LOCKED → COMMITTED → REVEALED → ENDED → ARCHIVED")
    void fullPaidLifecycle() {
        GuardContext noSecret = GuardContext.allSatisfied().withSecretSealed(false);
        assertThat(allow(DRAFT, SubmitForReveal, noSecret)).isEqualTo(AWAITING_SECRET);

        GuardContext all = GuardContext.allSatisfied();
        assertThat(allow(AWAITING_SECRET, SealSecret, all)).isEqualTo(READY);
        assertThat(allow(READY, PublishReveal, all)).isEqualTo(PUBLISHED);
        assertThat(allow(PUBLISHED, OpenLobby, all)).isEqualTo(LOBBY_OPEN);
        assertThat(allow(LOBBY_OPEN, LockVoting, all)).isEqualTo(LOCKED);
        assertThat(allow(LOCKED, CommitReveal, all)).isEqualTo(REVEAL_COMMITTED);
        assertThat(allow(REVEAL_COMMITTED, ReleaseOutcome, all)).isEqualTo(REVEALED);
        assertThat(allow(REVEALED, EndReveal, all)).isEqualTo(ENDED);
        assertThat(allow(ENDED, ArchiveReveal, all)).isEqualTo(ARCHIVED);
    }

    @Test
    @DisplayName("Secret already sealed at submit → straight to READY")
    void submitWithSealedSecretSkipsAwaiting() {
        assertThat(allow(DRAFT, SubmitForReveal, GuardContext.allSatisfied())).isEqualTo(READY);
    }

    @Test
    @DisplayName("Free path: DRAFT → GUESS_OPEN → (upgrade) → AWAITING_SECRET/READY")
    void guessPartyLifecycle() {
        assertThat(allow(DRAFT, PublishGuessParty, GuardContext.allSatisfied())).isEqualTo(GUESS_OPEN);
        assertThat(allow(GUESS_OPEN, UpgradeGuessParty,
                GuardContext.allSatisfied().withSecretSealed(false))).isEqualTo(AWAITING_SECRET);
        assertThat(allow(GUESS_OPEN, UpgradeGuessParty, GuardContext.allSatisfied())).isEqualTo(READY);
    }

    @Test
    @DisplayName("Host mode: ArmReveal locks from LOBBY_OPEN and commits from LOCKED")
    void armRevealPaths() {
        assertThat(allow(LOBBY_OPEN, ArmReveal, GuardContext.allSatisfied())).isEqualTo(LOCKED);
        assertThat(allow(LOCKED, ArmReveal, GuardContext.allSatisfied())).isEqualTo(REVEAL_COMMITTED);
    }

    // ---------- The invariant that pays the rent ----------

    @Test
    @DisplayName("INVARIANT: GUESS_OPEN can never reach LOBBY_OPEN / LOCKED / REVEAL_COMMITTED without upgrading")
    void guessPartyCannotReachRevealStates() {
        GuardContext all = GuardContext.allSatisfied();
        for (RevealCommand cmd : RevealCommand.values()) {
            var result = RevealStateMachine.evaluate(GUESS_OPEN, cmd, all);
            if (result instanceof RevealStateMachine.Allowed allowed) {
                assertThat(allowed.to())
                        .as("GUESS_OPEN + %s must not reach a reveal state", cmd)
                        .isNotIn(LOBBY_OPEN, LOCKED, REVEAL_COMMITTED, REVEALED);
            }
        }
        // And explicitly, the tempting shortcuts:
        assertThat(reject(GUESS_OPEN, OpenLobby, all).code()).isEqualTo("INVALID_TRANSITION");
        assertThat(reject(GUESS_OPEN, LockVoting, all).code()).isEqualTo("INVALID_TRANSITION");
        assertThat(reject(GUESS_OPEN, CommitReveal, all).code()).isEqualTo("INVALID_TRANSITION");
        assertThat(reject(GUESS_OPEN, ArmReveal, all).code()).isEqualTo("INVALID_TRANSITION");
        assertThat(reject(GUESS_OPEN, PublishReveal, all).code()).isEqualTo("INVALID_TRANSITION");
    }

    @Test
    @DisplayName("INVARIANT: cancellation is impossible at and after REVEAL_COMMITTED")
    void cannotCancelAfterCommitment() {
        GuardContext all = GuardContext.allSatisfied();
        for (RevealState s : new RevealState[]{REVEAL_COMMITTED, REVEALED, ENDED, ARCHIVED}) {
            assertThat(reject(s, CancelReveal, all).code()).isEqualTo("INVALID_TRANSITION");
        }
        // ...but possible everywhere before it.
        for (RevealState s : new RevealState[]{DRAFT, GUESS_OPEN, AWAITING_SECRET, READY, PUBLISHED, LOBBY_OPEN, LOCKED}) {
            assertThat(allow(s, CancelReveal, all)).isEqualTo(CANCELLED);
        }
    }

    @Test
    @DisplayName("EXHAUSTIVE: every (state, command) pair outside the table is rejected")
    void everyForbiddenPairIsRejected() {
        GuardContext all = GuardContext.allSatisfied();
        int allowed = 0;
        int rejected = 0;
        for (RevealState from : RevealState.values()) {
            for (RevealCommand cmd : RevealCommand.values()) {
                boolean inTable = !RevealStateMachine.targetsFor(from, cmd).isEmpty();
                var result = RevealStateMachine.evaluate(from, cmd, all);
                if (inTable) {
                    assertThat(result)
                            .as("table row %s + %s", from, cmd)
                            .isInstanceOf(RevealStateMachine.Allowed.class);
                    allowed++;
                } else {
                    assertThat(result)
                            .as("undeclared %s + %s must be rejected", from, cmd)
                            .isInstanceOf(RevealStateMachine.Rejected.class);
                    assertThat(((RevealStateMachine.Rejected) result).code()).isEqualTo("INVALID_TRANSITION");
                    rejected++;
                }
            }
        }
        // 12 states × 13 commands = 156 pairs; the table is the small minority.
        assertThat(allowed + rejected).isEqualTo(RevealState.values().length * RevealCommand.values().length);
        assertThat(rejected).isGreaterThan(allowed);
    }

    @Test
    @DisplayName("Terminal states accept nothing")
    void terminalStatesAreTerminal() {
        for (RevealCommand cmd : RevealCommand.values()) {
            reject(ARCHIVED, cmd, GuardContext.allSatisfied());
            reject(CANCELLED, cmd, GuardContext.allSatisfied());
        }
    }

    // ---------- Guards reject with the right code ----------

    @Test
    @DisplayName("Guards: missing entitlement, unsealed secret, failed preflight each get their own code")
    void guardsProduceSpecificCodes() {
        assertThat(reject(READY, PublishReveal,
                GuardContext.allSatisfied().withEntitlementActive(false)).code())
                .isEqualTo("ENTITLEMENT_REQUIRED");

        assertThat(reject(GUESS_OPEN, UpgradeGuessParty,
                GuardContext.allSatisfied().withEntitlementActive(false)).code())
                .isEqualTo("ENTITLEMENT_REQUIRED");

        assertThat(reject(GUESS_OPEN, UpgradeGuessParty,
                GuardContext.allSatisfied().withSeatGatingSatisfied(false)).code())
                .isEqualTo("SEAT_GATING");

        assertThat(reject(LOCKED, CommitReveal,
                GuardContext.allSatisfied().withSecretSealed(false)).code())
                .isEqualTo("SECRET_NOT_SEALED");

        assertThat(reject(LOCKED, ArmReveal,
                GuardContext.allSatisfied().withPreflightPassed(false)).code())
                .isEqualTo("PREFLIGHT_FAILED");

        assertThat(reject(REVEAL_COMMITTED, ReleaseOutcome,
                GuardContext.allSatisfied().withSecretSealed(false)).code())
                .isEqualTo("SECRET_NOT_SEALED");
    }

    @Test
    @DisplayName("A suspended reveal refuses every command")
    void suspendedRefusesEverything() {
        GuardContext suspended = new GuardContext(true, true, true, true, true, true, true);
        for (RevealCommand cmd : RevealCommand.values()) {
            assertThat(reject(PUBLISHED, cmd, suspended).code()).isEqualTo("SUSPENDED");
        }
    }

    // ---------- Legacy mapping (against the real production shapes) ----------

    private static Map<String, Object> doc(String status, String revealTime, Object... kv) {
        Map<String, Object> d = new HashMap<>();
        if (status != null) d.put("status", status);
        if (revealTime != null) d.put("revealTime", revealTime);
        for (int i = 0; i < kv.length; i += 2) d.put((String) kv[i], kv[i + 1]);
        return d;
    }

    @Test
    @DisplayName("Legacy mapping covers all four production shapes")
    void legacyMapping() {
        Instant now = Instant.parse("2026-07-24T12:00:00Z");

        assertThat(LegacyStateMapper.map(doc("ended", "2026-07-01T10:00:00Z"), now).state())
                .isEqualTo(ENDED);
        assertThat(LegacyStateMapper.map(doc(null, null, "isRevealed", true), now).state())
                .isEqualTo(ENDED);

        // waiting, far ahead → PUBLISHED; imminent → LOBBY_OPEN
        assertThat(LegacyStateMapper.map(doc("waiting", "2026-07-25T12:00:00Z"), now).state())
                .isEqualTo(PUBLISHED);
        assertThat(LegacyStateMapper.map(doc("waiting", "2026-07-24T12:20:00Z"), now).state())
                .isEqualTo(LOBBY_OPEN);
        // A reveal still 'waiting' whose time passed never fired: its lobby is
        // NOT open. 19 of the 135 production docs are exactly this shape.
        assertThat(LegacyStateMapper.map(doc("waiting", "2026-05-01T12:00:00Z"), now).state())
                .isEqualTo(PUBLISHED);

        assertThat(LegacyStateMapper.map(doc("live", "2026-07-24T12:10:00Z"), now).state())
                .isEqualTo(LOBBY_OPEN);

        // The 40 status-less abandoned drafts.
        assertThat(LegacyStateMapper.map(doc(null, "2026-01-18T18:00:00Z", "paymentStatus", "pending"), now).state())
                .isEqualTo(DRAFT);

        // pending + paid + scheduled is a real upcoming reveal, not a draft.
        assertThat(LegacyStateMapper.map(doc("pending", "2026-08-01T18:00:00Z", "paymentStatus", "completed"), now).state())
                .isEqualTo(PUBLISHED);
        assertThat(LegacyStateMapper.map(doc("pending", null, "paymentStatus", "pending"), now).state())
                .isEqualTo(AWAITING_SECRET);
    }

    @Test
    @DisplayName("Backfill is idempotent: an already-migrated doc keeps its state")
    void mappingIsIdempotent() {
        Instant now = Instant.now();
        Map<String, Object> migrated = doc("waiting", "2026-08-01T10:00:00Z", "state", "LOCKED");
        assertThat(LegacyStateMapper.map(migrated, now).state()).isEqualTo(LOCKED);
        assertThat(LegacyStateMapper.map(migrated, now).reason()).isEqualTo("already migrated");
    }

    @Test
    @DisplayName("A backfilled legacy reveal can complete its lifecycle")
    void backfilledLegacyRevealCompletes() {
        Instant now = Instant.now();
        Map<String, Object> legacy = doc("waiting", now.plusSeconds(600).toString(),
                "paymentStatus", "completed", "motherName", "Ana", "gender", "girl");

        RevealState state = LegacyStateMapper.map(legacy, now).state();
        assertThat(state).isEqualTo(LOBBY_OPEN);

        GuardContext ctx = GuardContext.fromRevealDoc(legacy);
        assertThat(ctx.secretSealed()).isTrue();       // legacy gender field counts as sealed
        assertThat(ctx.entitlementActive()).isTrue();  // paymentStatus completed

        state = allow(state, LockVoting, ctx);
        state = allow(state, CommitReveal, ctx);
        state = allow(state, ReleaseOutcome, ctx);
        state = allow(state, EndReveal, ctx);
        assertThat(state).isEqualTo(ENDED);
    }

    @Test
    @DisplayName("Legacy grandfathering: an untiered doc is entitled, an unpaid tiered one is not")
    void legacyGrandfathering() {
        assertThat(GuardContext.fromRevealDoc(doc(null, null)).entitlementActive()).isTrue();
        assertThat(GuardContext.fromRevealDoc(doc(null, null, "tier", "legacy")).entitlementActive()).isTrue();
        assertThat(GuardContext.fromRevealDoc(
                doc(null, null, "tier", "intimate", "paymentStatus", "pending")).entitlementActive()).isFalse();
    }
}
