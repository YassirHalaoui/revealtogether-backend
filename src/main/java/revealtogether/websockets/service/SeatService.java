package revealtogether.websockets.service;

import com.google.firebase.auth.FirebaseAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import revealtogether.websockets.domain.SeatRecord;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.domain.SessionStatus;
import revealtogether.websockets.dto.JoinResponse;
import revealtogether.websockets.repository.RedisRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Tiered-pricing seat gate.
 *
 * Contract (locked with frontend, Jul 2026):
 * - Legacy reveals (no tier field) are never gated and never tracked — rule 3.
 * - Grand tier (tier set, seatLimit null) is tracked (for host stats) but never gated.
 * - Capped tiers gate NEW seats at ceil(seatLimit * graceFactor); the response
 *   always reports the marketing seatLimit, never the grace gate.
 * - Within watchWindow of revealTime, over-cap devices get watch_only instead
 *   of at_capacity (they can watch the moment live, not participate).
 * - The SCARD-then-SADD claim is deliberately not atomic: a concurrent-join race
 *   can over-admit by 1-2 seats, which the grace buffer absorbs. Do not "fix"
 *   this with Lua — the product accepts fail-open.
 */
@Service
public class SeatService {

    private static final Logger log = LoggerFactory.getLogger(SeatService.class);

    private final RedisRepository redisRepository;
    private final SessionService sessionService;
    private final FirebaseService firebaseService;
    private final double graceFactor;
    private final Duration watchWindow;

    public SeatService(
            RedisRepository redisRepository,
            SessionService sessionService,
            FirebaseService firebaseService,
            @Value("${app.seats.grace-factor:1.2}") double graceFactor,
            @Value("${app.seats.watch-window-minutes:45}") long watchWindowMinutes
    ) {
        this.redisRepository = redisRepository;
        this.sessionService = sessionService;
        this.firebaseService = firebaseService;
        this.graceFactor = graceFactor;
        this.watchWindow = Duration.ofMinutes(watchWindowMinutes);
    }

    /**
     * @return the join decision, or null if the session doesn't exist anywhere (controller -> 404)
     */
    public JoinResponse join(String sessionId, String visitorId, String email, String idToken) {
        // Read-only lookup first: Redis, then Firestore. We only WRITE to Redis
        // (lazy load) for tiered, still-active sessions that actually need seat state.
        Optional<Session> sessionOpt = sessionService.getSession(sessionId);
        boolean inRedis = sessionOpt.isPresent();
        if (!inRedis) {
            sessionOpt = firebaseService.getSessionFromFirestore(sessionId);
            if (sessionOpt.isEmpty()) {
                return null;
            }
        }
        Session session = sessionOpt.get();

        // Legacy reveal: no tracking, no gating, no Redis writes. Rule 3.
        if (!session.isTiered()) {
            return JoinResponse.joined(0, null);
        }

        // Ended: nothing to participate in; viewing is handled client-side.
        if (session.status() == SessionStatus.ENDED) {
            return JoinResponse.watchOnly(seatCountSafe(sessionId, inRedis), session.seatLimit());
        }

        // Tiered + active + not cached: load into Redis (restores seats from Firestore).
        if (!inRedis) {
            sessionService.loadIntoRedis(session, false);
            inRedis = true;
        }

        long joined = redisRepository.seatCount(sessionId);
        Integer limit = session.seatLimit();

        // Host exemption: verified owner never consumes a seat.
        if (isOwner(idToken, session.ownerId())) {
            return JoinResponse.joined(joined, limit);
        }

        // Already allowed on this device — idempotent.
        if (redisRepository.hasSeatAccess(sessionId, visitorId)) {
            return JoinResponse.joined(joined, limit);
        }

        String emailHash = hashEmail(email);

        // Email merge: same person on a second device reuses their seat.
        if (emailHash != null) {
            String seatOwner = redisRepository.getSeatOwnerByEmailHash(sessionId, emailHash);
            if (seatOwner != null) {
                redisRepository.grantSeatAccess(sessionId, visitorId);
                firebaseService.saveSeat(sessionId, new SeatRecord(visitorId, emailHash, false));
                log.debug("Email-merged device onto existing seat: session={}, visitor={}", sessionId, visitorId);
                return JoinResponse.joined(joined, limit);
            }
        }

        // Capacity gate (grace-buffered). Grand tier (null limit) never gates.
        long gate = limit == null ? Long.MAX_VALUE : (long) Math.ceil(limit * graceFactor);
        if (joined >= gate) {
            boolean inFinalWindow = Instant.now().isAfter(session.revealTime().minus(watchWindow));
            log.info("Seat gate hit: session={}, joined={}, limit={}, finalWindow={}",
                    sessionId, joined, limit, inFinalWindow);
            return inFinalWindow
                    ? JoinResponse.watchOnly(joined, limit)
                    : JoinResponse.atCapacity(joined, limit);
        }

        // Claim a fresh seat.
        redisRepository.claimSeat(sessionId, visitorId);
        if (emailHash != null) {
            redisRepository.mapEmailToSeat(sessionId, emailHash, visitorId);
        }
        firebaseService.saveSeat(sessionId, new SeatRecord(visitorId, emailHash, true));
        return JoinResponse.joined(redisRepository.seatCount(sessionId), limit);
    }

    /**
     * Vote-path check: is this device allowed to participate on a capped session?
     * Used by VoteService; join() with auto-claim is attempted first by the caller.
     */
    public boolean canParticipate(String sessionId, String visitorId) {
        return redisRepository.hasSeatAccess(sessionId, visitorId);
    }

    /**
     * Host-dashboard stats. Caller is responsible for owner auth.
     * @return {joined, limit} or null if the session doesn't exist.
     */
    public JoinResponse stats(String sessionId) {
        Optional<Session> sessionOpt = sessionService.getSession(sessionId);
        boolean inRedis = sessionOpt.isPresent();
        if (!inRedis) {
            sessionOpt = firebaseService.getSessionFromFirestore(sessionId);
            if (sessionOpt.isEmpty()) {
                return null;
            }
        }
        Session session = sessionOpt.get();
        if (!session.isTiered()) {
            return JoinResponse.joined(0, null);
        }
        return JoinResponse.joined(seatCountSafe(sessionId, inRedis), session.seatLimit());
    }

    /** Seat count from Redis when cached, else from durable Firestore records. */
    private long seatCountSafe(String sessionId, boolean inRedis) {
        if (inRedis) {
            long count = redisRepository.seatCount(sessionId);
            if (count > 0) {
                return count;
            }
        }
        return firebaseService.getSeatRecords(sessionId).stream()
                .filter(SeatRecord::countsAsSeat)
                .count();
    }

    private boolean isOwner(String idToken, String ownerId) {
        if (idToken == null || idToken.isBlank() || ownerId == null) {
            return false;
        }
        try {
            String uid = FirebaseAuth.getInstance().verifyIdToken(idToken).getUid();
            return ownerId.equals(uid);
        } catch (Exception e) {
            // Invalid/expired token or Firebase not initialized (tests): treat as guest.
            log.debug("Host token verification failed, treating as guest: {}", e.getMessage());
            return false;
        }
    }

    /** SHA-256 of lowercased/trimmed email — raw guest emails never touch storage here. */
    static String hashEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(email.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed on every JVM; unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
