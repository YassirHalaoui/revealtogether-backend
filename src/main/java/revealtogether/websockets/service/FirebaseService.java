package revealtogether.websockets.service;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import revealtogether.websockets.domain.ChatMessage;
import revealtogether.websockets.domain.Session;
import revealtogether.websockets.domain.SessionStatus;
import revealtogether.websockets.domain.VoteCount;
import revealtogether.websockets.domain.VoteOption;
import revealtogether.websockets.domain.VoteRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

@Service
public class FirebaseService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseService.class);
    private static final String REVEALS_COLLECTION = "reveals";

    private final Firestore firestore;

    public FirebaseService(@Nullable Firestore firestore) {
        this.firestore = firestore;
    }

    public void saveSession(Session session, String theme, String paymentStatus) {
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
        if (session.motherName() != null) data.put("motherName", session.motherName());
        if (session.fatherName() != null) data.put("fatherName", session.fatherName());
        if (theme != null) data.put("theme", theme);
        data.put("paymentStatus", paymentStatus != null ? paymentStatus : "pending");

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

    /**
     * Writes an individual vote to reveals/{sessionId}/votes/{voteId}.
     * Fire-and-forget: does not block the vote response.
     */
    public void saveVote(String sessionId, String visitorId, String name, String option) {
        if (firestore == null) {
            log.warn("Firebase not configured. Skipping vote save.");
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("visitorId", visitorId);
        data.put("name", name);
        data.put("vote", option);
        data.put("timestamp", Instant.now().toString());

        String voteId = UUID.randomUUID().toString();

        var future = firestore.collection(REVEALS_COLLECTION)
                .document(sessionId)
                .collection("votes")
                .document(voteId)
                .set(data);

        future.addListener(() -> {
            try {
                future.get();
                log.debug("Vote saved to Firestore: session={}, visitor={}", sessionId, visitorId);
            } catch (Exception e) {
                log.error("Failed to save vote to Firestore: session={}, visitor={}, error={}", sessionId, visitorId, e.getMessage());
            }
        }, Runnable::run);
    }

    /**
     * Atomically increments the vote count on the reveal document.
     * Fire-and-forget. Ensures Firestore always has current counts even if
     * Redis expires before the reveal ends.
     */
    public void incrementVoteCount(String sessionId, String option) {
        if (firestore == null) {
            log.warn("Firebase not configured. Skipping vote count increment.");
            return;
        }

        String field = "votes." + option;

        var future = firestore.collection(REVEALS_COLLECTION)
                .document(sessionId)
                .update(field, FieldValue.increment(1));

        future.addListener(() -> {
            try {
                future.get();
                log.debug("Vote count incremented in Firestore: session={}, option={}", sessionId, option);
            } catch (Exception e) {
                log.error("Failed to increment vote count in Firestore: session={}, option={}, error={}", sessionId, option, e.getMessage());
            }
        }, Runnable::run);
    }

    /**
     * Reconstructs a Session from Firestore when Redis has expired.
     * Used as fallback for getSessionState on old/ended sessions.
     */
    public Optional<Session> getSessionFromFirestore(String sessionId) {
        if (firestore == null) {
            return Optional.empty();
        }

        try {
            var doc = firestore.collection(REVEALS_COLLECTION)
                    .document(sessionId)
                    .get()
                    .get();

            if (!doc.exists()) {
                return Optional.empty();
            }

            Map<String, Object> data = doc.getData();
            String ownerId = (String) data.get("ownerId");
            String gender = (String) data.get("gender");
            String status = (String) data.get("status");
            String revealTime = (String) data.get("revealTime");
            String createdAt = (String) data.get("createdAt");
            String motherName = (String) data.get("motherName");
            String fatherName = (String) data.get("fatherName");

            if (ownerId == null || gender == null || status == null || revealTime == null || createdAt == null) {
                log.warn("Incomplete Firestore document for session {}", sessionId);
                return Optional.empty();
            }

            Session session = new Session(
                    sessionId,
                    ownerId,
                    VoteOption.fromValue(gender),
                    SessionStatus.fromValue(status),
                    Instant.parse(revealTime),
                    Instant.parse(createdAt),
                    motherName,
                    fatherName
            );
            log.info("Session {} reconstructed from Firestore (Redis expired)", sessionId);
            return Optional.of(session);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to reconstruct session {} from Firestore", sessionId, e);
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Reads all individual vote records from reveals/{sessionId}/votes subcollection.
     * Used as fallback when Redis has expired.
     */
    public List<VoteRecord> getVoteRecords(String sessionId) {
        if (firestore == null) {
            log.warn("Firebase not configured. Cannot fetch vote records.");
            return List.of();
        }

        try {
            List<QueryDocumentSnapshot> docs = firestore.collection(REVEALS_COLLECTION)
                    .document(sessionId)
                    .collection("votes")
                    .get()
                    .get()
                    .getDocuments();

            List<VoteRecord> records = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                try {
                    String visitorId = doc.getString("visitorId");
                    String name = doc.getString("name");
                    String vote = doc.getString("vote");
                    String timestamp = doc.getString("timestamp");
                    if (visitorId != null && name != null && vote != null) {
                        records.add(new VoteRecord(
                                visitorId,
                                name,
                                VoteOption.fromValue(vote),
                                timestamp != null ? Instant.parse(timestamp) : Instant.now()
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Skipping malformed vote doc {}: {}", doc.getId(), e.getMessage());
                }
            }
            records.sort(java.util.Comparator.comparing(VoteRecord::timestamp));
            return records;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to fetch vote records from Firestore for session {}", sessionId, e);
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /**
     * Deletes the Firestore document and its entire votes subcollection.
     * Safe to call even if the document doesn't exist.
     */
    public void deleteSession(String sessionId) {
        if (firestore == null) {
            log.warn("Firebase not configured. Skipping Firestore delete for session {}", sessionId);
            return;
        }

        try {
            // Delete all docs in votes subcollection first
            var votesDocs = firestore.collection(REVEALS_COLLECTION)
                    .document(sessionId)
                    .collection("votes")
                    .get()
                    .get()
                    .getDocuments();

            for (QueryDocumentSnapshot voteDoc : votesDocs) {
                voteDoc.getReference().delete().get();
            }

            // Delete the parent document (no-op if it doesn't exist)
            firestore.collection(REVEALS_COLLECTION)
                    .document(sessionId)
                    .delete()
                    .get();

            log.info("Deleted Firestore session {} ({} vote docs removed)", sessionId, votesDocs.size());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to delete Firestore session {}", sessionId, e);
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
