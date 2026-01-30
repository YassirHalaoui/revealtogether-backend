package revealtogether.websockets.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.dto.SessionCreateRequest;
import revealtogether.websockets.dto.SessionResponse;
import revealtogether.websockets.dto.SessionStateResponse;
import revealtogether.websockets.service.FirebaseService;
import revealtogether.websockets.service.SessionService;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class RevealController {

    private static final Logger log = LoggerFactory.getLogger(RevealController.class);

    private final SessionService sessionService;
    private final FirebaseService firebaseService;
    private final String baseUrl;

    public RevealController(
            SessionService sessionService,
            FirebaseService firebaseService,
            @Value("${app.base-url:https://revealtogether.com}") String baseUrl
    ) {
        this.sessionService = sessionService;
        this.firebaseService = firebaseService;
        this.baseUrl = baseUrl;
    }

    @PostMapping("/reveals")
    public ResponseEntity<SessionResponse> createReveal(@Valid @RequestBody SessionCreateRequest request) {
        log.info("Creating reveal session for owner: {}", request.ownerId());

        Session session = sessionService.createSession(request);
        firebaseService.saveSession(session);

        SessionResponse response = SessionResponse.from(session, baseUrl);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/reveals/{sessionId}")
    public ResponseEntity<?> getReveal(@PathVariable String sessionId) {
        // First check Redis for active session
        var sessionOpt = sessionService.getSession(sessionId);
        if (sessionOpt.isPresent()) {
            Session session = sessionOpt.get();
            return ResponseEntity.ok(SessionResponse.from(session, baseUrl));
        }

        // If not in Redis, check Firebase for ended session
        Map<String, Object> firebaseData = firebaseService.getRevealData(sessionId);
        if (firebaseData != null) {
            return ResponseEntity.ok(firebaseData);
        }

        return ResponseEntity.notFound().build();
    }

    @GetMapping("/session/{sessionId}/state")
    public ResponseEntity<SessionStateResponse> getSessionState(
            @PathVariable String sessionId,
            @RequestParam(required = false, defaultValue = "") String visitorId
    ) {
        SessionStateResponse state = sessionService.getSessionState(sessionId, visitorId);

        if (state == null) {
            // Check Firebase for ended session
            Map<String, Object> firebaseData = firebaseService.getRevealData(sessionId);
            if (firebaseData != null) {
                // Session ended, return data from Firebase
                return ResponseEntity.ok().build();
            }
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(state);
    }
}
