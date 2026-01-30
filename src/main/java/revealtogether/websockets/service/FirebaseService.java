package revealtogether.websockets.service;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import revealtogether.websockets.domain.ChatMessage;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.domain.VoteCount;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Service
public class FirebaseService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseService.class);
    private static final String REVEALS_COLLECTION = "reveals";

    private final Firestore firestore;

    public FirebaseService(@Nullable Firestore firestore) {
        this.firestore = firestore;
    }

    public void saveSession(Session session) {
        if (firestore == null) {
            log.warn("Firebase not configured. Skipping session save.");
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", session.sessionId());
        data.put("ownerId", session.ownerId());
        data.put("gender", session.gender().getValue());
        data.put("status", session.status().getValue());
        data.put("revealTime", session.revealTime().toString());
        data.put("createdAt", session.createdAt().toString());

        try {
            firestore.collection(REVEALS_COLLECTION)
                    .document(session.sessionId())
                    .set(data, SetOptions.merge())
                    .get();
            log.info("Session {} saved to Firebase", session.sessionId());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to save session to Firebase", e);
            Thread.currentThread().interrupt();
        }
    }

    public void saveRevealResults(Session session, VoteCount votes, List<ChatMessage> chatHistory) {
        if (firestore == null) {
            log.warn("Firebase not configured. Skipping results save.");
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", session.sessionId());
        data.put("ownerId", session.ownerId());
        data.put("gender", session.gender().getValue());
        data.put("status", "ended");
        data.put("revealTime", session.revealTime().toString());
        data.put("createdAt", session.createdAt().toString());
        data.put("endedAt", Instant.now().toString());

        // Results
        Map<String, Object> results = new HashMap<>();
        results.put("boyVotes", votes.boy());
        results.put("girlVotes", votes.girl());
        results.put("totalVotes", votes.total());
        data.put("results", results);

        // Chat history (convert to serializable format)
        List<Map<String, Object>> chatData = chatHistory.stream()
                .map(msg -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", msg.name());
                    m.put("message", msg.message());
                    m.put("visitorId", msg.visitorId());
                    m.put("timestamp", msg.timestamp().toString());
                    return m;
                })
                .toList();
        data.put("chatHistory", chatData);

        try {
            firestore.collection(REVEALS_COLLECTION)
                    .document(session.sessionId())
                    .set(data, SetOptions.merge())
                    .get();
            log.info("Reveal results saved for session {}", session.sessionId());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to save reveal results to Firebase", e);
            Thread.currentThread().interrupt();
        }
    }

    public Map<String, Object> getRevealData(String sessionId) {
        if (firestore == null) {
            log.warn("Firebase not configured. Cannot fetch reveal data.");
            return null;
        }

        try {
            var doc = firestore.collection(REVEALS_COLLECTION)
                    .document(sessionId)
                    .get()
                    .get();

            if (doc.exists()) {
                return doc.getData();
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to fetch reveal data from Firebase", e);
            Thread.currentThread().interrupt();
        }

        return null;
    }
}
