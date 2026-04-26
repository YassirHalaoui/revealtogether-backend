package revealtogether.websockets.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import revealtogether.websockets.domain.SessionStatus;
import revealtogether.websockets.domain.VoteCount;
import revealtogether.websockets.domain.VoteOption;
import revealtogether.websockets.domain.VoteRecord;
import revealtogether.websockets.dto.VoteRequest;
import revealtogether.websockets.dto.VoteResponse;
import revealtogether.websockets.repository.RedisRepository;

@Service
public class VoteService {

    private static final Logger log = LoggerFactory.getLogger(VoteService.class);

    private final RedisRepository redisRepository;
    private final SessionService sessionService;
    private final FirebaseService firebaseService;

    public VoteService(RedisRepository redisRepository, SessionService sessionService, FirebaseService firebaseService) {
        this.redisRepository = redisRepository;
        this.sessionService = sessionService;
        this.firebaseService = firebaseService;
    }

    public VoteResponse castVote(String sessionId, VoteRequest request) {
        // Check rate limit
        if (redisRepository.isRateLimited(request.visitorId())) {
            log.debug("Vote rate limited for visitor: {}", request.visitorId());
            return VoteResponse.rateLimited();
        }

        // Check session exists and is active — load from Firestore if not yet in Redis
        var sessionOpt = sessionService.getSession(sessionId);
        if (sessionOpt.isEmpty()) {
            var firestoreSession = firebaseService.getSessionFromFirestore(sessionId);
            if (firestoreSession.isEmpty()) {
                log.warn("Vote attempted on non-existent session: {}", sessionId);
                return new VoteResponse(false, "Session not found");
            }
            sessionService.loadIntoRedis(firestoreSession.get(), false);
            log.info("Lazy-loaded session into Redis on first vote: session={}, status={}",
                    sessionId, firestoreSession.get().status());
            sessionOpt = sessionService.getSession(sessionId);
        }

        var session = sessionOpt.get();
        if (session.status() == SessionStatus.ENDED) {
            log.debug("Vote attempted on ended session: {}", sessionId);
            return VoteResponse.sessionEnded();
        }

        // Check if already voted
        if (redisRepository.hasVoted(sessionId, request.visitorId())) {
            log.debug("Duplicate vote attempt by visitor: {} on session: {}",
                    request.visitorId(), sessionId);
            return VoteResponse.alreadyVoted();
        }

        // Record the vote
        VoteOption option = VoteOption.fromValue(request.option());
        boolean recorded = redisRepository.recordVote(sessionId, request.visitorId(), option);

        if (recorded) {
            // Save individual vote record for display
            VoteRecord record = VoteRecord.create(request.visitorId(), request.name(), option);
            redisRepository.saveVoteRecord(sessionId, record);

            // Persist to Firestore subcollection and increment running count (fire-and-forget)
            firebaseService.saveVote(sessionId, request.visitorId(), request.name(), option.getValue());
            firebaseService.incrementVoteCount(sessionId, option.getValue());

            log.info("Vote recorded: session={}, visitor={}, option={}, name={}",
                    sessionId, request.visitorId(), option, request.name());
            return VoteResponse.ok();
        }

        return VoteResponse.alreadyVoted();
    }

    public VoteCount getVotes(String sessionId) {
        return redisRepository.getVotes(sessionId);
    }

    public boolean hasVoted(String sessionId, String visitorId) {
        return redisRepository.hasVoted(sessionId, visitorId);
    }
}
