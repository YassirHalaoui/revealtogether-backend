# Frontend Integration Guide

**Forget the old Express.js WebSocket.** This is a new STOMP-based system built for real-time reliability.

---

## Tech Stack (Backend)

- Java 21 + Spring Boot 4.x
- STOMP over WebSocket + SockJS fallback
- Redis for live data
- Firebase Firestore for persistence

---

## Base URLs

| Environment | REST API | WebSocket |
|-------------|----------|-----------|
| Local | `http://localhost:8181` | `ws://localhost:8181/ws` |
| Production | `https://your-domain.com` | `wss://your-domain.com/ws` |

---

## Frontend Requirements

Use **@stomp/stompjs** + **sockjs-client** (not raw WebSocket).

```bash
npm install @stomp/stompjs sockjs-client
```

---

## Connection Setup

```typescript
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const client = new Client({
  webSocketFactory: () => new SockJS('http://localhost:8181/ws'),
  reconnectDelay: 5000,
  heartbeatIncoming: 4000,
  heartbeatOutgoing: 4000,
});

client.onConnect = () => {
  console.log('Connected');
  // Subscribe to topics here
};

client.onStompError = (frame) => {
  console.error('STOMP error', frame);
};

client.activate();
```

---

## REST Endpoints

### 1. Create Reveal Session

```
POST /api/reveals
Content-Type: application/json

{
  "ownerId": "user-uuid",
  "gender": "boy" | "girl",
  "revealTime": "2026-01-28T15:00:00Z"
}

Response 201:
{
  "sessionId": "abc-123",
  "status": "waiting",
  "shareableLink": "https://domain.com/reveal/abc-123",
  "revealTime": "2026-01-28T15:00:00Z"
}
```

### 2. Get Session Details

```
GET /api/reveals/{sessionId}

Response 200:
{
  "sessionId": "abc-123",
  "status": "waiting" | "live" | "ended",
  "revealTime": "2026-01-28T15:00:00Z",
  "shareableLink": "..."
}
```

### 3. Get Session State (Reconnection)

```
GET /api/session/{sessionId}/state?visitorId={visitorId}

Response 200:
{
  "sessionId": "abc-123",
  "status": "live",
  "votes": { "boy": 42, "girl": 38 },
  "hasVoted": true,
  "revealedGender": null  // Only set when status=ended
}
```

---

## WebSocket Topics

### Subscribe: Vote Counts (Batched)

```typescript
client.subscribe(`/topic/votes/${sessionId}`, (message) => {
  const votes = JSON.parse(message.body);
  // { "boy": 42, "girl": 38 }
  updateVoteDisplay(votes);
});
```

**Updates every 200ms** when votes change (batched for performance).

### Subscribe: Individual Vote Events (For Floating Animations)

```typescript
client.subscribe(`/topic/vote-events/${sessionId}`, (message) => {
  const event = JSON.parse(message.body);
  // { "visitorId": "xxx", "name": "Alice", "option": "boy", "timestamp": "..." }
  showFloatingVote(event.name, event.option);  // Show "💙 Alice" floating up
});
```

**Instant broadcast** - use this for real-time floating vote animations with names!

### Subscribe: Chat Messages

```typescript
client.subscribe(`/topic/chat/${sessionId}`, (message) => {
  const chat = JSON.parse(message.body);
  // { "name": "Alice", "message": "So excited!", "timestamp": "..." }
  appendChatMessage(chat);
});
```

### Subscribe: Vote Response (Your Vote)

```typescript
client.subscribe(`/topic/vote-response/${sessionId}`, (message) => {
  const response = JSON.parse(message.body);
  // { "success": true, "message": "Vote recorded" }
  // { "success": false, "message": "Already voted" }
});
```

### Send: Cast Vote

```typescript
client.publish({
  destination: `/app/vote/${sessionId}`,
  body: JSON.stringify({
    option: "boy" | "girl",  // MUST be "option", not "vote"
    visitorId: "visitor-uuid",  // Generate once, store in localStorage
    name: "Alice"  // Optional: for floating vote display
  })
});
```

### Send: Chat Message

```typescript
client.publish({
  destination: `/app/chat/${sessionId}`,
  body: JSON.stringify({
    name: "Alice",
    message: "Can't wait!",
    visitorId: "visitor-uuid"
  })
});
```

---

## Session Lifecycle

```
WAITING → LIVE → ENDED
```

1. **WAITING**: Session created, not yet accepting votes
2. **LIVE**: Voting open, real-time updates flowing
3. **ENDED**: Reveal time passed, gender shown

Session auto-transitions:
- WAITING → LIVE: 5 minutes before reveal time
- LIVE → ENDED: At reveal time

---

## Visitor ID

Generate once per browser, store in localStorage:

```typescript
const getVisitorId = () => {
  let id = localStorage.getItem('visitorId');
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem('visitorId', id);
  }
  return id;
};
```

Used for:
- Duplicate vote prevention (1 vote per visitor)
- Rate limiting (1 req/sec per visitor)

---

## Reconnection Handling

On reconnect, fetch current state via REST then resubscribe:

```typescript
client.onConnect = async () => {
  // 1. Get current state
  const state = await fetch(`/api/session/${sessionId}/state?visitorId=${visitorId}`);
  const data = await state.json();

  // 2. Update UI with current votes
  updateVoteDisplay(data.votes);
  setHasVoted(data.hasVoted);

  // 3. Resubscribe to live updates
  client.subscribe(`/topic/votes/${sessionId}`, ...);
  client.subscribe(`/topic/chat/${sessionId}`, ...);
};
```

---

## Rate Limits

| Action | Limit |
|--------|-------|
| Vote | 1 per visitor (total) |
| Chat | 1 message/sec per visitor |

Exceeding returns `{ "success": false, "message": "Rate limited" }`.

---

## Example: Full React Hook

```typescript
import { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

export function useRevealSession(sessionId: string) {
  const [votes, setVotes] = useState({ boy: 0, girl: 0 });
  const [hasVoted, setHasVoted] = useState(false);
  const [status, setStatus] = useState<'waiting' | 'live' | 'ended'>('waiting');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const clientRef = useRef<Client | null>(null);
  const visitorId = getVisitorId();

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(`${API_URL}/ws`),
      reconnectDelay: 5000,
    });

    client.onConnect = async () => {
      // Fetch initial state
      const res = await fetch(`${API_URL}/api/session/${sessionId}/state?visitorId=${visitorId}`);
      const state = await res.json();
      setVotes(state.votes);
      setHasVoted(state.hasVoted);
      setStatus(state.status);

      // Subscribe to live updates
      client.subscribe(`/topic/votes/${sessionId}`, (msg) => {
        setVotes(JSON.parse(msg.body));
      });

      client.subscribe(`/topic/chat/${sessionId}`, (msg) => {
        setMessages((prev) => [...prev, JSON.parse(msg.body)]);
      });

      client.subscribe(`/topic/vote-response/${sessionId}`, (msg) => {
        const res = JSON.parse(msg.body);
        if (res.success) setHasVoted(true);
      });
    };

    client.activate();
    clientRef.current = client;

    return () => client.deactivate();
  }, [sessionId]);

  const castVote = (option: 'boy' | 'girl') => {
    clientRef.current?.publish({
      destination: `/app/vote/${sessionId}`,
      body: JSON.stringify({ option, visitorId }),  // MUST use "option" field
    });
  };

  const sendChat = (name: string, message: string) => {
    clientRef.current?.publish({
      destination: `/app/chat/${sessionId}`,
      body: JSON.stringify({ name, message, visitorId }),
    });
  };

  return { votes, hasVoted, status, messages, castVote, sendChat };
}
```

---

## Key Differences from Old System

| Old (Express) | New (Spring STOMP) |
|---------------|-------------------|
| Raw WebSocket | STOMP protocol |
| Manual reconnect | Auto-reconnect built-in |
| Immediate broadcast | Batched 200ms (smoother) |
| No fallback | SockJS fallback for old browsers |
| Manual state sync | REST endpoint for reconnection |

---

## Testing Locally

1. Start backend:
   ```bash
   docker compose up -d
   source .env.local && ./mvnw spring-boot:run
   ```

2. Test WebSocket with browser console or Postman WebSocket client

3. Health check: `curl http://localhost:8181/actuator/health`

---

*Last updated: January 2026*
