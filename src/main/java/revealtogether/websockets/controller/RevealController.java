package revealtogether.websockets.controller;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.domain.SessionStatus;
import revealtogether.websockets.dto.PendingRevealRequest;
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
    private final SimpMessagingTemplate messagingTemplate;
    private final String baseUrl;

    public RevealController(
            SessionService sessionService,
            FirebaseService firebaseService,
            SimpMessagingTemplate messagingTemplate,
            @Value("${app.base-url:https://revealtogether.com}") String baseUrl
    ) {
        this.sessionService = sessionService;
        this.firebaseService = firebaseService;
        this.messagingTemplate = messagingTemplate;
        this.baseUrl = baseUrl;
    }

    @PostMapping("/reveals/pending")
    public ResponseEntity<?> upsertPendingReveal(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody PendingRevealRequest request
    ) {
        // Verify Firebase ID token
        String uid;
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing token");
            }
            String idToken = authHeader.substring(7);
            uid = FirebaseAuth.getInstance().verifyIdToken(idToken).getUid();
        } catch (FirebaseAuthException e) {
            log.warn("Invalid Firebase token: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid token");
        }

        String revealId = firebaseService.upsertPendingReveal(
                uid,
                request.gender(),
                request.motherName(),
                request.fatherName(),
                request.revealTime(),
                request.theme()
        );

        if (revealId == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to upsert reveal");
        }

        return ResponseEntity.ok(Map.of("revealId", revealId));
    }

    @PostMapping("/reveals")
    public ResponseEntity<SessionResponse> createReveal(@Valid @RequestBody SessionCreateRequest request) {
        log.info("Creating reveal session for owner: {}", request.ownerId());

        // If existingRevealId provided, update that doc instead of creating a new one
        if (request.existingRevealId() != null && !request.existingRevealId().isBlank()) {
            Map<String, Object> existing = firebaseService.getRevealData(request.existingRevealId());
            if (existing != null) {
                String existingOwner = (String) existing.get("ownerId");
                if (!request.ownerId().equals(existingOwner)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
                // Never overwrite a completed reveal — return it as-is
                String existingPaymentStatus = (String) existing.get("paymentStatus");
                if ("completed".equals(existingPaymentStatus)) {
                    log.warn("Attempted to overwrite completed reveal {} — returning as-is", request.existingRevealId());
                    Session session = sessionService.createSessionWithId(request, request.existingRevealId());
                    return ResponseEntity.status(HttpStatus.CREATED).body(SessionResponse.from(session, baseUrl));
                }
                // Update pending reveal — preserve original createdAt by not re-saving session object
                Session session = sessionService.createSessionWithId(request, request.existingRevealId());
                firebaseService.updateSession(session, request.theme(), request.paymentStatus());
                return ResponseEntity.status(HttpStatus.CREATED).body(SessionResponse.from(session, baseUrl));
            }
            // existingRevealId not found — fall through and create fresh
        }

        Session session = sessionService.createSession(request);
        firebaseService.saveSession(session, request.theme(), request.paymentStatus());

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

    @DeleteMapping("/reveals/{sessionId}")
    public ResponseEntity<?> deleteReveal(
            @PathVariable String sessionId,
            @RequestParam String ownerId
    ) {
        // Check Redis first, then Firestore (handles expired Redis case)
        String docOwnerId = null;
        var sessionOpt = sessionService.getSession(sessionId);
        if (sessionOpt.isPresent()) {
            docOwnerId = sessionOpt.get().ownerId();
        } else {
            // Redis expired — check Firestore for ownership
            Map<String, Object> firestoreData = firebaseService.getRevealData(sessionId);
            if (firestoreData == null) {
                return ResponseEntity.notFound().build();
            }
            docOwnerId = (String) firestoreData.get("ownerId");
        }

        // Authorization check
        if (docOwnerId == null || !docOwnerId.equals(ownerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // If session is live, broadcast "deleted" so connected clients redirect away
        if (sessionOpt.isPresent() && sessionOpt.get().status() == SessionStatus.LIVE) {
            messagingTemplate.convertAndSend(
                    "/topic/votes/" + sessionId,
                    (Object) Map.of("type", "deleted")
            );
        }

        // Clean up Redis (no-op if already expired)
        sessionService.deleteSession(sessionId);

        // Clean up Firestore doc + votes subcollection
        firebaseService.deleteSession(sessionId);

        log.info("Reveal {} deleted by owner {}", sessionId, ownerId);
        return ResponseEntity.noContent().build();
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
