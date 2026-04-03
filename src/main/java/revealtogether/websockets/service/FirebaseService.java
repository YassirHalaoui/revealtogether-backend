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

    /**
     * Updates an existing reveal doc without overwriting createdAt.
     * Used when existingRevealId is provided in POST /api/reveals.
     */
    public void updateSession(Session session, String theme, String paymentStatus) {
        if (firestore == null) {
            log.warn("Firebase not configured. Skipping session update.");
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", session.sessionId());
        data.put("ownerId", session.ownerId());
        data.put("gender", session.gender().getValue());
        data.put("status", session.status().getValue());
        data.put("revealTime", session.revealTime().toString());
        data.put("updatedAt", FieldValue.serverTimestamp());
        if (session.motherName() != null) data.put("motherName", session.motherName());
        if (session.fatherName() != null) data.put("fatherName", session.fatherName());
        if (theme != null) data.put("theme", theme);
        data.put("paymentStatus", paymentStatus != null ? paymentStatus : "pending");

        try {
            firestore.collection(REVEALS_COLLECTION)
                    .document(session.sessionId())
                    .set(data, SetOptions.merge())
                    .get();
            log.info("Session {} updated in Firebase", session.sessionId());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to update session in Firebase", e);
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
     * Queries Firestore for WAITING sessions whose revealTime is within the next windowSeconds.
     * Used by RevealScheduler to lazily load sessions into Redis just before they go live.
     * Firestore reads are free — this replaces Redis polling for waiting sessions entirely.
     */
    public List<Session> getUpcomingSessions(int windowSeconds) {
        if (firestore == null) {
            return List.of();
        }

        try {
            String now = Instant.now().toString();
            String cutoff = Instant.now().plusSeconds(windowSeconds).toString();

            List<QueryDocumentSnapshot> docs = firestore.collection(REVEALS_COLLECTION)
                    .whereEqualTo("status", "waiting")
                    .whereGreaterThanOrEqualTo("revealTime", now)
                    .whereLessThanOrEqualTo("revealTime", cutoff)
                    .get()
                    .get()
                    .getDocuments();

            List<Session> sessions = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                try {
                    Map<String, Object> data = doc.getData();
                    sessions.add(new Session(
                            (String) data.get("sessionId"),
                            (String) data.get("ownerId"),
                            VoteOption.fromValue((String) data.get("gender")),
                            SessionStatus.fromValue((String) data.get("status")),
                            Instant.parse((String) data.get("revealTime")),
                            Instant.parse((String) data.get("createdAt")),
                            (String) data.get("motherName"),
                            (String) data.get("fatherName")
                    ));
                } catch (Exception e) {
                    log.warn("Skipping malformed session doc {}: {}", doc.getId(), e.getMessage());
                }
            }
            log.debug("Found {} upcoming sessions within {}s window", sessions.size(), windowSeconds);
            return sessions;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to query upcoming sessions from Firestore", e);
            Thread.currentThread().interrupt();
            return List.of();
        }
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

    /**
     * Upserts a pending reveal for a user.
     * - If the user already has a pending reveal, updates it and returns its ID.
     * - If the user only has completed reveals, creates a fresh pending reveal.
     * - Never creates more than one pending reveal per user.
     * - Idempotent: safe to call multiple times.
     */
    public String upsertPendingReveal(String ownerId, String gender, String motherName,
                                      String fatherName, java.time.Instant revealTime, String theme) {
        if (firestore == null) {
            log.warn("Firebase not configured. Cannot upsert pending reveal.");
            return null;
        }

        try {
            // Query for existing pending reveal for this user
            var query = firestore.collection(REVEALS_COLLECTION)
                    .whereEqualTo("ownerId", ownerId)
                    .whereEqualTo("paymentStatus", "pending")
                    .orderBy("createdAt", com.google.cloud.firestore.Query.Direction.DESCENDING)
                    .limit(1)
                    .get()
                    .get();

            String revealId;
            Map<String, Object> data = new HashMap<>();
            data.put("ownerId", ownerId);
            data.put("createdBy", ownerId);
            data.put("gender", gender);
            data.put("paymentStatus", "pending");
            data.put("status", "waiting");
            data.put("updatedAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
            if (motherName != null) data.put("motherName", motherName);
            if (fatherName != null) data.put("fatherName", fatherName);
            if (revealTime != null) data.put("revealTime", revealTime.toString());
            if (theme != null) data.put("theme", theme);

            if (!query.isEmpty()) {
                // Update existing pending reveal
                var doc = query.getDocuments().get(0);
                revealId = doc.getId();
                firestore.collection(REVEALS_COLLECTION)
                        .document(revealId)
                        .set(data, SetOptions.merge())
                        .get();
                log.info("Updated existing pending reveal {} for owner {}", revealId, ownerId);
            } else {
                // Create new pending reveal
                revealId = UUID.randomUUID().toString();
                data.put("sessionId", revealId);
                data.put("createdAt", com.google.cloud.firestore.FieldValue.serverTimestamp());
                firestore.collection(REVEALS_COLLECTION)
                        .document(revealId)
                        .set(data)
                        .get();
                log.info("Created new pending reveal {} for owner {}", revealId, ownerId);
            }

            return revealId;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to upsert pending reveal for owner {}", ownerId, e);
            Thread.currentThread().interrupt();
            return null;
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
