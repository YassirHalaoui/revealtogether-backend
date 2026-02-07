# RevealTogether

## Backend Architecture Document

**Version 1.1 — February 2026**

---

## Executive Summary

RevealTogether is a virtual gender reveal platform enabling synchronized real-time experiences for geographically distributed guests. This document defines the backend architecture optimized for reliability, scalability, and cost-effectiveness.

---

## Technology Stack

| Layer | Technology | Justification |
|-------|------------|---------------|
| Runtime | Java 21 + Spring Boot 4.x | Developer expertise, virtual threads, production-proven |
| Real-time | STOMP over WebSocket + SockJS | Bidirectional comms, handles chat + votes, fallback support |
| Live Cache | Upstash Redis (Pay-as-you-go) | Fast pub/sub, ephemeral session data, $0.2/100K commands |
| Persistence | Firebase Firestore | Existing infrastructure, permanent storage |
| Hosting | Railway ($5/month) | Already configured, sufficient for MVP scale |

---

## Maven Dependencies

```xml
<dependencies>
    <!-- Core -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    
    <!-- WebSocket -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
    
    <!-- Redis -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    
    <!-- Firebase Admin -->
    <dependency>
        <groupId>com.google.firebase</groupId>
        <artifactId>firebase-admin</artifactId>
        <version>9.2.0</version>
    </dependency>
</dependencies>
```

---

## System Architecture

**Data Flow:**

```
┌─────────────┐         ┌─────────────────┐         ┌─────────────┐
│   Next.js   │◄──WS───►│   Spring Boot   │◄───────►│   Redis     │
│  (Frontend) │         │    (Backend)    │         │   (Live)    │
└─────────────┘         └────────┬────────┘         └─────────────┘
                                 │
                                 ▼ (on reveal end)
                        ┌─────────────────┐
                        │    Firebase     │
                        │  (Persistent)   │
                        └─────────────────┘
```

---

## WebSocket Topics

| Topic | Direction | Purpose |
|-------|-----------|---------|
| `/topic/votes/{sessionId}` | Server → Client | Batched vote count updates (every 500ms) |
| `/topic/chat/{sessionId}` | Server → Client | Chat messages broadcast |
| `/app/vote/{sessionId}` | Client → Server | Cast vote (boy/girl) |
| `/app/chat/{sessionId}` | Client → Server | Send chat message |

---

## Redis Data Model

| Key Pattern | Type | Content | TTL |
|-------------|------|---------|-----|
| `votes:{sessionId}` | Hash | `{ "boy": 42, "girl": 38 }` | 24h after reveal |
| `voters:{sessionId}` | Set | `[ visitorId1, visitorId2, ... ]` | 24h after reveal |
| `chat:{sessionId}` | List | `[ {name, msg, visitorId, ts}, ... ]` | 24h after reveal |
| `session:{sessionId}` | Hash | `{ status, revealTime, createdAt }` | 24h after reveal |
| `dirty:{sessionId}` | String | `"1"` if votes changed | 24h after reveal |
| `ratelimit:{visitorId}` | String | Rate limit counter | 1 second |

---

## Guest Identification (Anonymous)

Guests are anonymous — no authentication required. Identification is handled via browser localStorage:

1. Guest opens shared link: `revealtogether.com/r/{sessionId}`
2. Check localStorage for `visitorId`
   - Exists → fetch existing vote status
   - Doesn't exist → generate UUID, store it
3. Guest enters display name (stored in localStorage)
4. Guest votes → server checks Redis SET `voters:{sessionId}`
   - Not found → accept vote, add to SET
   - Found → reject, return "already voted"
5. Guest can chat using display name
6. On reveal → everyone sees result simultaneously

**Duplicate Vote Prevention:**

localStorage UUID is sufficient for this use case. This is a family event, not an election. Determined users could bypass by clearing storage, but the social context makes this unlikely.

---

## Nominal Flow

### 1. Create Reveal Session

```
POST /api/reveals
→ Generate sessionId (UUID)
→ Save to Firebase (owner, revealTime, gender)
→ Init Redis keys with TTL
→ Return sessionId + shareable link
```

### 2. Guest Joins

```
WebSocket connect to /ws
→ SUBSCRIBE /topic/votes/{sessionId}
→ SUBSCRIBE /topic/chat/{sessionId}
→ Server sends current state snapshot (votes + recent 50 messages)
```

### 3. Guest Votes

```
SEND /app/vote/{sessionId} { "option": "boy", "visitorId": "uuid" }
→ Check Redis SET voters:{sessionId} for duplicate
→ If new: Redis HINCRBY votes:{sessionId} boy 1
→ Set dirty:{sessionId} = 1
→ Batched broadcast picks it up within 500ms
```

### 4. Guest Sends Chat

```
SEND /app/chat/{sessionId} { "name": "Alice", "msg": "So excited!", "visitorId": "uuid" }
→ Rate limit check (1 msg/sec per visitorId)
→ Validate message length (max 280 chars)
→ Redis LPUSH chat:{sessionId} (capped at 500 messages)
→ Broadcast immediately to /topic/chat/{sessionId}
```

### 5. Reveal Ends

```
Triggered by scheduled job at revealTime:
→ Fetch final votes from Redis
→ Fetch chat history from Redis
→ Save to Firebase: reveals/{sessionId}/results
→ Broadcast "reveal" event with gender + final counts
→ Set Redis keys TTL to 1 hour (cleanup)
```

### 6. View Past Reveal

```
GET /api/reveals/{sessionId}
→ Fetch from Firebase
→ Return final votes + chat history + reveal result
```

---

## Batched Broadcast Strategy

To handle high-frequency voting (up to 1000 concurrent users) without overwhelming clients, votes are batched server-side and broadcast every 500ms. This is imperceptible to users but prevents UI freezing.

**ActiveSessionRegistry** (in-memory `ConcurrentHashMap`) eliminates all Redis polling when idle:
- Sessions are registered on creation, unregistered on end
- Schedulers check the local registry (zero Redis cost when no active sessions)
- Safety net: reconciles with Redis every 60s (handles server restarts)

```java
// Zero Redis commands when idle — registry is checked in-memory
@Scheduled(fixedRate = 500)
public void broadcastVotes() {
    if (!sessionRegistry.hasActiveSessions()) return; // FREE check

    for (String sessionId : sessionRegistry.getActiveSessions()) {
        if (redisRepository.isDirtyAndClear(sessionId)) {
            VoteCount votes = redisRepository.getVotes(sessionId);
            messagingTemplate.convertAndSend("/topic/votes/" + sessionId, votes);
        }
    }
}
```

---

## Edge Cases & Handling

| Scenario | Handling |
|----------|----------|
| User votes twice | Redis SADD `voters:{sessionId}` → reject if visitorId exists |
| WebSocket disconnects | SockJS auto-reconnects; client fetches current state via REST |
| 1000 votes in 1 second | Batching (500ms) smooths broadcast; Redis handles load |
| Redis goes down | Log error, degrade gracefully, alert owner |
| Reveal owner closes browser | Reveal continues; scheduled job ends it at revealTime |
| Guest joins after reveal ended | Check session status → return results from Firebase |
| Chat spam | Rate limit: 1 msg/sec per visitorId (Redis SETEX 1s TTL) |
| Orphan session (never ends) | Redis TTL auto-expires all keys after 24h |
| Invalid vote option | Validate option ∈ {"boy", "girl"} → reject otherwise |
| Message too long | Validate max 280 chars → truncate or reject |
| Upstash daily limit approached | Monitor usage; batch reads locally; alert if >80% |

---

## Security Considerations

| Concern | Solution |
|---------|----------|
| Vote manipulation | Validate session exists; one vote per visitorId via Redis SET |
| Chat impersonation | Display name is user-chosen (acceptable for party context) |
| Unauthorized session access | SessionId is UUID (unguessable); optional access code for private reveals |
| CORS | Restrict origins to revealtogether.com |
| DoS on vote endpoint | Rate limit: 1 vote request/sec per IP |
| XSS in chat | Sanitize all user input before storage and display |

---

## Upstash Usage Optimization

Upstash pay-as-you-go: $0.2 per 100K commands. Optimizations to minimize cost:

- **ActiveSessionRegistry:** In-memory tracking of active sessions. Schedulers skip Redis entirely when idle — **zero commands when no events are live**
- **Dirty flag pattern:** Only read vote counts when they've actually changed (`isDirtyAndClear`)
- **Reconciliation:** Registry syncs with Redis every 60s as safety net (1,440 commands/day idle)
- **Estimated cost per event:** ~9K commands for a 30-min reveal = ~$0.02
- **Idle cost:** ~$0.003/day (reconciliation only)

---

## Reconnection Flow

1. WebSocket disconnects (network issue, mobile sleep, etc.)
2. SockJS automatically attempts reconnection
3. On successful reconnect, client sends SUBSCRIBE messages
4. Client calls `GET /api/session/{sessionId}/state` to fetch:
   - Current vote counts
   - Last 50 chat messages
   - Session status (live/ended)
5. Client merges state and resumes normal subscription flow

---

## Observability

| Component | Implementation |
|-----------|----------------|
| Health Check | `/actuator/health` — Railway monitors this |
| Logging | Structured JSON logs; sessionId + visitorId in MDC |
| Metrics | Micrometer: active_sessions, votes_total, ws_connections |
| Alerts | Log when Upstash usage >80%; error on Redis connection failure |

---

## Environment Variables (Railway)

```
UPSTASH_REDIS_URL=rediss://default:xxx@xxx.upstash.io:6379  # Note: rediss:// (double s) for TLS
FIREBASE_CREDENTIALS=base64-encoded-service-account-json
PORT=8080
ALLOWED_ORIGINS=https://www.revealtogether.com,https://revealtogether.com
LOG_LEVEL=INFO
```

---

## Input Validation Rules

| Field | Rule |
|-------|------|
| sessionId | UUID format, must exist in Redis or Firebase |
| visitorId | UUID format, generated client-side |
| vote option | Enum: "boy" or "girl" only |
| display name | Max 50 chars, sanitized |
| chat message | Max 280 chars, sanitized, non-empty |

---

## Cost Summary

| Service | Tier | Monthly Cost |
|---------|------|--------------|
| Railway (Spring Boot) | Starter | $5 (includes compute) |
| Upstash Redis | Pay-as-you-go | ~$0.10 (estimated, idle + few events) |
| Firebase Firestore | Spark (Free) | $0 |
| **Total** | | **~$5.10/month** |

---

## Future Scaling Considerations

Current architecture supports single Spring Boot instance. If RevealTogether grows significantly:

- **Multiple instances:** Add Redis pub/sub for cross-instance WebSocket messaging
- **Sticky sessions:** Simpler alternative — route same session to same instance
- **Upstash scaling:** Pay-as-you-go scales automatically; consider Upstash Pro for guaranteed throughput
- **Global latency:** Consider edge deployment (Fly.io, Railway regions) for international guests

---

## Implementation Notes

- **Firebase init:** Initialize Firebase Admin SDK on application startup, not lazily (avoids cold start delay)
- **SockJS fallback:** Required for older browsers and corporate proxies that block WebSocket
- **Chat capping:** Keep only last 500 messages in Redis (LTRIM after LPUSH)
- **Graceful shutdown:** On app shutdown, flush any pending data to Firebase before exit
- **ActiveSessionRegistry:** `ConcurrentHashMap`-backed in-memory set of active session IDs. Registered on session creation, unregistered on session end. Reconciles with Redis every 60s. Schedulers check this (free) instead of hitting Redis.
- **Docker image:** Must use Debian-based `eclipse-temurin:21-jre` (NOT Alpine). Alpine's musl libc crashes with gRPC/Netty native SSL libraries used by Firebase.
- **Redis TLS:** Upstash requires `rediss://` (double s) protocol. Single `redis://` will cause JVM SIGSEGV crashes.
- **CORS config:** Uses `setAllowedOriginPatterns` (not `setAllowedOrigins`) for SockJS compatibility with credentials. Origins are trimmed from comma-separated `ALLOWED_ORIGINS` env var.

---

*Document updated February 2026. Production-deployed on Railway.*
