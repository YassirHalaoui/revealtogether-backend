# RevealTogether Backend - Development Reference

## Project Overview

RevealTogether is a virtual gender reveal platform enabling synchronized real-time experiences for geographically distributed guests.

---

## Technology Stack

| Layer | Technology | Version |
|-------|------------|---------|
| Runtime | Java 21 + Spring Boot 4.x | Virtual threads enabled |
| Real-time | STOMP over WebSocket + SockJS | Bidirectional comms |
| Live Cache | Upstash Redis | Pay-as-you-go ($0.2/100K cmds) |
| Persistence | Firebase Firestore | For permanent storage |
| Hosting | Railway | $5/month |

---

## Package Structure

```
revealtogether.websockets/
├── config/           # Configuration classes
│   ├── WebSocketConfig.java    # STOMP + SockJS configuration
│   ├── RedisConfig.java        # Redis template setup
│   ├── FirebaseConfig.java     # Firebase Admin SDK init
│   ├── CorsConfig.java         # CORS settings
│   └── MetricsConfig.java      # Custom Micrometer metrics
├── domain/           # Domain models (Java records)
│   ├── Session.java
│   ├── SessionStatus.java      # WAITING, LIVE, ENDED
│   ├── VoteOption.java         # BOY, GIRL
│   ├── VoteCount.java
│   └── ChatMessage.java
├── dto/              # Request/Response DTOs
│   ├── VoteRequest.java
│   ├── VoteResponse.java
│   ├── ChatRequest.java
│   ├── SessionCreateRequest.java
│   ├── SessionResponse.java
│   ├── SessionStateResponse.java
│   └── RevealEvent.java
├── repository/       # Redis operations
│   └── RedisRepository.java
├── service/          # Business logic
│   ├── ActiveSessionRegistry.java  # In-memory session cache (zero Redis when idle)
│   ├── SessionService.java
│   ├── VoteService.java
│   ├── ChatService.java
│   └── FirebaseService.java
├── controller/       # REST endpoints
│   └── RevealController.java
├── websocket/        # WebSocket handlers
│   ├── VoteController.java
│   └── ChatController.java
├── scheduler/        # Scheduled tasks
│   ├── VoteBroadcastScheduler.java  # 500ms batched broadcasts (dirty flag)
│   └── RevealScheduler.java         # Auto-reveal trigger
└── exception/        # Error handling
    └── GlobalExceptionHandler.java
```

---

## WebSocket Topics

| Topic | Direction | Purpose |
|-------|-----------|---------|
| `/topic/votes/{sessionId}` | Server → Client | Batched vote updates (500ms) |
| `/topic/chat/{sessionId}` | Server → Client | Chat messages broadcast |
| `/app/vote/{sessionId}` | Client → Server | Cast vote (boy/girl) |
| `/app/chat/{sessionId}` | Client → Server | Send chat message |

---

## Redis Data Model

| Key Pattern | Type | TTL |
|-------------|------|-----|
| `session:{sessionId}` | Hash | 24h |
| `votes:{sessionId}` | Hash | 24h |
| `voters:{sessionId}` | Set | 24h |
| `chat:{sessionId}` | List | 24h |
| `dirty:{sessionId}` | String | 24h |
| `ratelimit:{visitorId}` | String | 1s |

---

## REST API Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/reveals` | POST | Create new reveal session |
| `/api/reveals/{sessionId}` | GET | Get reveal details/results |
| `/api/session/{sessionId}/state` | GET | Get current state (reconnection) |

---

## Input Validation Rules

| Field | Rule |
|-------|------|
| sessionId | UUID format |
| visitorId | UUID format |
| vote option | "boy" or "girl" only |
| display name | Max 50 chars, sanitized |
| chat message | Max 280 chars, sanitized, non-empty |

---

## Rate Limits

- Vote: 1 request/sec per visitorId
- Chat: 1 message/sec per visitorId

---

## Environment Variables

```
UPSTASH_REDIS_URL=rediss://default:xxx@xxx.upstash.io:6379
FIREBASE_CREDENTIALS=base64-encoded-service-account-json
PORT=8080
BASE_URL=https://revealtogether.com
ALLOWED_ORIGINS=https://www.revealtogether.com,https://revealtogether.com
LOG_LEVEL=INFO
```

---

## Key Implementation Notes

1. **Vote Batching**: Broadcast every 500ms using dirty flag pattern
2. **Duplicate Prevention**: Redis SET `voters:{sessionId}` tracks who voted
3. **Chat Capping**: Keep only last 500 messages (LTRIM after LPUSH)
4. **Graceful Shutdown**: Flush pending data to Firebase on shutdown
5. **SockJS Fallback**: Required for older browsers/corporate proxies
6. **Firebase Init**: Eager initialization on startup (avoid cold start)
7. **ActiveSessionRegistry**: In-memory `ConcurrentHashMap` of active session IDs. Schedulers check this instead of Redis — **zero Redis commands when idle**. Reconciles with Redis every 60s as a safety net for restarts. Sessions are registered on creation, unregistered on end.
8. **Redis Protocol**: Must use `rediss://` (double s) for Upstash TLS. Alpine Docker images crash with native SSL — use Debian-based `eclipse-temurin:21-jre`.
9. **CORS**: Uses `setAllowedOriginPatterns` (not `setAllowedOrigins`) for SockJS compatibility. Origins trimmed from comma-separated env var.

---

## Development Commands

```bash
# Run locally
./mvnw spring-boot:run

# Run tests
./mvnw test

# Build
./mvnw clean package
```

---

## Implementation Status

- [x] Project configuration (pom.xml, application.yaml)
- [x] Domain models & DTOs
- [x] WebSocket configuration (STOMP + SockJS)
- [x] Redis configuration & repository
- [x] Firebase configuration & service
- [x] Vote service with duplicate prevention
- [x] Chat service with rate limiting
- [x] REST API controllers
- [x] Scheduled jobs (broadcast, reveal)
- [x] Input validation & error handling
- [x] Observability (metrics, health via Actuator)

---

## Test Coverage

**89 tests total (85 passing, 4 skipped WebSocket timing tests)**

| Test Class | Tests | Description |
|------------|-------|-------------|
| RedisRepositoryTest | 14 | Redis operations, TTL, dirty flags |
| VoteServiceTest | 9 | Vote casting, duplicates, rate limiting |
| ChatServiceTest | 10 | Chat messages, rate limiting, validation |
| SessionServiceTest | 8 | Session lifecycle (create, activate, end) |
| RevealControllerTest | 10 | REST API endpoints |
| WebSocketIntegrationTest | 5 | WebSocket connection and vote operations |
| SchedulerTest | 7 | Vote broadcast and reveal scheduler |

### Running Tests

```bash
# Run all tests (requires Docker for Testcontainers)
./mvnw test

# Run specific test class
./mvnw test -Dtest=VoteServiceTest

# Run with verbose output
./mvnw test -Dsurefire.useFile=false
```

---

## Future Enhancements

- [ ] WebSocket event listeners for connection tracking
- [ ] Graceful shutdown hook for Firebase flush
- [x] Integration tests with Testcontainers Redis
- [ ] API documentation (OpenAPI/Swagger)
- [ ] Optional access code for private reveals

---

*Last updated: February 2026*
