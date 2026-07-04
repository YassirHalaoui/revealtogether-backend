package revealtogether.websockets.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import revealtogether.websockets.BaseIntegrationTest;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.domain.SessionStatus;
import revealtogether.websockets.domain.VoteOption;
import revealtogether.websockets.dto.JoinResponse;
import revealtogether.websockets.dto.VoteRequest;
import revealtogether.websockets.dto.VoteResponse;
import revealtogether.websockets.repository.RedisRepository;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SeatService Integration Tests")
class SeatServiceTest extends BaseIntegrationTest {

    @Autowired
    private SeatService seatService;

    @Autowired
    private VoteService voteService;

    @Autowired
    private RedisRepository redisRepository;

    private static String vid() {
        return UUID.randomUUID().toString();
    }

    /** Seeds a session directly into Redis (tests run with Firebase disabled). */
    private Session seedSession(String tier, Integer seatLimit, Instant revealTime) {
        Session session = new Session(
                UUID.randomUUID().toString(), "owner-" + vid(), VoteOption.GIRL,
                SessionStatus.WAITING, revealTime, Instant.now(), "Mom", "Dad",
                tier, seatLimit
        );
        redisRepository.saveSession(session);
        return session;
    }

    private static final Instant FAR_FUTURE = Instant.now().plusSeconds(7200);

    @Test
    @DisplayName("Legacy session (no tier): joined, uncapped, no tracking")
    void legacySessionIsUncapped() {
        Session session = seedSession(null, null, FAR_FUTURE);

        JoinResponse response = seatService.join(session.sessionId(), vid(), null, null);

        assertThat(response.status()).isEqualTo(JoinResponse.JOINED);
        assertThat(response.limit()).isNull();
        assertThat(redisRepository.seatCount(session.sessionId())).isZero();
    }

    @Test
    @DisplayName("Capped session: fresh claims admitted up to grace gate, then at_capacity")
    void capacityGateWithGraceBuffer() {
        Session session = seedSession("celebration", 2, FAR_FUTURE); // gate = ceil(2*1.2) = 3

        assertThat(seatService.join(session.sessionId(), vid(), null, null).status())
                .isEqualTo(JoinResponse.JOINED);
        assertThat(seatService.join(session.sessionId(), vid(), null, null).status())
                .isEqualTo(JoinResponse.JOINED);
        assertThat(seatService.join(session.sessionId(), vid(), null, null).status())
                .isEqualTo(JoinResponse.JOINED);

        JoinResponse fourth = seatService.join(session.sessionId(), vid(), null, null);
        assertThat(fourth.status()).isEqualTo(JoinResponse.AT_CAPACITY);
        assertThat(fourth.limit()).isEqualTo(2); // marketing number, never the grace gate
        assertThat(fourth.joined()).isEqualTo(3);
    }

    @Test
    @DisplayName("Rejoin is idempotent: same device never consumes a second seat")
    void rejoinIsIdempotent() {
        Session session = seedSession("intimate", 10, FAR_FUTURE);
        String visitor = vid();

        seatService.join(session.sessionId(), visitor, null, null);
        long after1 = redisRepository.seatCount(session.sessionId());
        seatService.join(session.sessionId(), visitor, null, null);

        assertThat(redisRepository.seatCount(session.sessionId())).isEqualTo(after1).isEqualTo(1);
    }

    @Test
    @DisplayName("REGRESSION: rejoin with email binds the seat; second device with same email merges")
    void emailMergeAfterRejoinBind() {
        Session session = seedSession("celebration", 2, FAR_FUTURE); // gate 3

        // Fill the session completely: A (no email) + two others.
        String deviceA = vid();
        seatService.join(session.sessionId(), deviceA, null, null);
        seatService.join(session.sessionId(), vid(), null, null);
        seatService.join(session.sessionId(), vid(), null, null);
        assertThat(redisRepository.seatCount(session.sessionId())).isEqualTo(3);

        // A rejoins WITH an email -> must bind the email to A's existing seat.
        JoinResponse rejoin = seatService.join(session.sessionId(), deviceA, "Grandma@Test.com ", null);
        assertThat(rejoin.status()).isEqualTo(JoinResponse.JOINED);

        // A brand-new device with the same email (different casing) reuses A's seat
        // instead of hitting at_capacity, and consumes NO new seat.
        String deviceB = vid();
        JoinResponse merged = seatService.join(session.sessionId(), deviceB, "grandma@test.com", null);
        assertThat(merged.status()).isEqualTo(JoinResponse.JOINED);
        assertThat(redisRepository.seatCount(session.sessionId())).isEqualTo(3);
        assertThat(seatService.canParticipate(session.sessionId(), deviceB)).isTrue();

        // A device WITHOUT the email is still gated.
        assertThat(seatService.join(session.sessionId(), vid(), null, null).status())
                .isEqualTo(JoinResponse.AT_CAPACITY);
    }

    @Test
    @DisplayName("Within 45min of reveal, over-cap devices get watch_only instead of at_capacity")
    void watchOnlyInsideFinalWindow() {
        Session session = seedSession("intimate", 1, Instant.now().plusSeconds(600)); // gate = ceil(1.2) = 2

        seatService.join(session.sessionId(), vid(), null, null);
        seatService.join(session.sessionId(), vid(), null, null);

        assertThat(seatService.join(session.sessionId(), vid(), null, null).status())
                .isEqualTo(JoinResponse.WATCH_ONLY);
    }

    @Test
    @DisplayName("Vote path enforces the cap: over-cap visitor rejected, seated visitor accepted")
    void votePathEnforcesCap() {
        Session session = seedSession("intimate", 1, FAR_FUTURE); // gate 2
        redisRepository.initializeVotes(session.sessionId());

        String seated = vid();
        seatService.join(session.sessionId(), seated, null, null);
        seatService.join(session.sessionId(), vid(), null, null); // fills the gate

        // Unseated visitor: vote auto-claim fails -> at capacity, with visitorId echoed.
        String blocked = vid();
        VoteResponse rejected = voteService.castVote(session.sessionId(),
                new VoteRequest("boy", blocked, "Blocked"));
        assertThat(rejected.success()).isFalse();
        assertThat(rejected.message()).isEqualTo("At capacity");
        assertThat(rejected.visitorId()).isEqualTo(blocked);

        // Seated visitor votes fine.
        VoteResponse accepted = voteService.castVote(session.sessionId(),
                new VoteRequest("girl", seated, "Seated"));
        assertThat(accepted.success()).isTrue();
        assertThat(accepted.visitorId()).isEqualTo(seated);
    }
}
