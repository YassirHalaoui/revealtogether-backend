package revealtogether.websockets.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import revealtogether.websockets.dto.VoteEvent;
import revealtogether.websockets.dto.VoteRequest;
import revealtogether.websockets.dto.VoteResponse;
import revealtogether.websockets.service.ActiveSessionRegistry;
import revealtogether.websockets.service.VoteService;

@Controller
public class VoteController {

    private static final Logger log = LoggerFactory.getLogger(VoteController.class);

    private final VoteService voteService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ActiveSessionRegistry sessionRegistry;

    public VoteController(
            VoteService voteService,
            SimpMessagingTemplate messagingTemplate,
            ActiveSessionRegistry sessionRegistry
    ) {
        this.voteService = voteService;
        this.messagingTemplate = messagingTemplate;
        this.sessionRegistry = sessionRegistry;
    }

    @MessageMapping("/vote/{sessionId}")
    @SendTo("/topic/vote-response/{sessionId}")
    public VoteResponse vote(
            @DestinationVariable String sessionId,
            VoteRequest request
    ) {
        log.debug("Vote received: session={}, visitor={}, option={}, name={}",
                sessionId, request.visitorId(), request.option(), request.name());

        VoteResponse response = voteService.castVote(sessionId, request);

        // Broadcast individual vote event for floating animations
        if (response.success()) {
            VoteEvent event = VoteEvent.create(
                    request.visitorId(),
                    request.name(),
                    request.option()
            );
            messagingTemplate.convertAndSend("/topic/vote-events/" + sessionId, event);
            log.debug("Broadcast vote event: session={}, name={}, option={}",
                    sessionId, request.name(), request.option());

            // VoteBroadcastScheduler skips non-live sessions; broadcast directly so counts update before the 30-min window.
            if (!sessionRegistry.getLiveSessions().contains(sessionId)) {
                var votes = voteService.getVotes(sessionId);
                messagingTemplate.convertAndSend("/topic/votes/" + sessionId, votes);
                log.info("Direct count broadcast (non-live session): session={}, votes={}", sessionId, votes);
            }
        }

        return response;
    }
}
