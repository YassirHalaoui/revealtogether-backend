# Simple Test App - Backend Verification

A minimal React app to test WebSocket voting and chat in isolation.

---

## Quick Setup

```bash
npx create-react-app test-reveal --template typescript
cd test-reveal
npm install @stomp/stompjs sockjs-client
npm install --save-dev @types/sockjs-client
```

---

## Replace `src/App.tsx`

```tsx
import { useState, useEffect, useRef } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const API_URL = 'http://localhost:8181';

// Generate visitor ID once
const getVisitorId = (): string => {
  let id = localStorage.getItem('testVisitorId');
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem('testVisitorId', id);
  }
  return id;
};

interface Votes {
  boy: number;
  girl: number;
}

interface ChatMessage {
  name: string;
  message: string;
  timestamp: string;
}

// Individual vote event from backend (for floating animations)
interface VoteEvent {
  visitorId: string;
  name: string;
  option: 'boy' | 'girl';
  timestamp: string;
}

// Local floating vote for animation
interface FloatingVote {
  id: number;
  name: string;
  option: 'boy' | 'girl';
}

function App() {
  const [sessionId, setSessionId] = useState<string>('');
  const [connected, setConnected] = useState(false);
  const [votes, setVotes] = useState<Votes>({ boy: 0, girl: 0 });
  const [hasVoted, setHasVoted] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [floatingVotes, setFloatingVotes] = useState<FloatingVote[]>([]);
  const [chatInput, setChatInput] = useState('');
  const [nameInput, setNameInput] = useState('Guest');
  const [status, setStatus] = useState('');

  const clientRef = useRef<Client | null>(null);
  const floatingIdRef = useRef(0);
  const visitorId = getVisitorId();

  // Create a new session
  const createSession = async () => {
    try {
      const res = await fetch(`${API_URL}/api/reveals`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ownerId: visitorId,
          gender: 'boy',
          revealTime: new Date(Date.now() + 3600000).toISOString() // 1 hour from now
        })
      });
      const data = await res.json();
      setSessionId(data.sessionId);
      setStatus(`Session created: ${data.sessionId}`);
    } catch (err) {
      setStatus(`Error: ${err}`);
    }
  };

  // Connect to existing session
  const connectToSession = () => {
    if (!sessionId) {
      setStatus('Enter a session ID first');
      return;
    }

    const client = new Client({
      webSocketFactory: () => new SockJS(`${API_URL}/ws`),
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      debug: (str) => console.log('[STOMP]', str)
    });

    client.onConnect = async () => {
      setConnected(true);
      setStatus('Connected!');

      // Fetch initial state
      try {
        const res = await fetch(`${API_URL}/api/session/${sessionId}/state?visitorId=${visitorId}`);
        const state = await res.json();
        setVotes(state.votes || { boy: 0, girl: 0 });
        setHasVoted(state.hasVoted || false);
        setStatus(`Connected - Votes: Boy ${state.votes?.boy || 0}, Girl ${state.votes?.girl || 0}`);
      } catch (err) {
        console.error('Failed to fetch state:', err);
      }

      // Subscribe to vote count updates (batched every 200ms)
      client.subscribe(`/topic/votes/${sessionId}`, (msg: IMessage) => {
        const newVotes: Votes = JSON.parse(msg.body);
        console.log('Vote count update:', newVotes);
        setVotes(newVotes);
      });

      // Subscribe to individual vote events (for floating animations with names!)
      client.subscribe(`/topic/vote-events/${sessionId}`, (msg: IMessage) => {
        const event: VoteEvent = JSON.parse(msg.body);
        console.log('Vote event received:', event);
        addFloatingVote(event.name, event.option);
      });

      // Subscribe to chat
      client.subscribe(`/topic/chat/${sessionId}`, (msg: IMessage) => {
        const chatMsg: ChatMessage = JSON.parse(msg.body);
        console.log('Chat received:', chatMsg);
        setMessages(prev => [...prev, chatMsg]);
      });

      // Subscribe to vote response (personal confirmation)
      client.subscribe(`/topic/vote-response/${sessionId}`, (msg: IMessage) => {
        const response = JSON.parse(msg.body);
        console.log('Vote response:', response);
        if (response.success) {
          setHasVoted(true);
          setStatus('Vote recorded!');
        } else {
          setStatus(`Vote failed: ${response.message}`);
        }
      });
    };

    client.onStompError = (frame) => {
      setStatus(`Error: ${frame.headers.message}`);
    };

    client.onDisconnect = () => {
      setConnected(false);
      setStatus('Disconnected');
    };

    client.activate();
    clientRef.current = client;
  };

  // Add floating vote animation with name
  const addFloatingVote = (name: string, option: 'boy' | 'girl') => {
    const id = ++floatingIdRef.current;
    setFloatingVotes(prev => [...prev, { id, name, option }]);

    // Remove after animation
    setTimeout(() => {
      setFloatingVotes(prev => prev.filter(v => v.id !== id));
    }, 3000);
  };

  // Cast vote
  const castVote = (option: 'boy' | 'girl') => {
    if (!clientRef.current || !connected) {
      setStatus('Not connected');
      return;
    }

    clientRef.current.publish({
      destination: `/app/vote/${sessionId}`,
      body: JSON.stringify({
        option,      // MUST be "option", not "vote"
        visitorId,
        name: nameInput || 'Guest'  // Include name for floating display
      })
    });
    setStatus(`Voting for ${option}...`);
  };

  // Send chat
  const sendChat = () => {
    if (!clientRef.current || !connected || !chatInput.trim()) return;

    clientRef.current.publish({
      destination: `/app/chat/${sessionId}`,
      body: JSON.stringify({
        name: nameInput || 'Guest',
        message: chatInput.trim(),
        visitorId
      })
    });
    setChatInput('');
  };

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      clientRef.current?.deactivate();
    };
  }, []);

  return (
    <div style={{ padding: 20, fontFamily: 'Arial', maxWidth: 800, margin: '0 auto' }}>
      <h1>WebSocket Test App</h1>

      {/* Status */}
      <div style={{
        padding: 10,
        marginBottom: 20,
        background: connected ? '#d4edda' : '#f8d7da',
        borderRadius: 4
      }}>
        {status || 'Not connected'}
      </div>

      {/* Session Controls */}
      <div style={{ marginBottom: 20, padding: 15, border: '1px solid #ddd', borderRadius: 4 }}>
        <h3>1. Session</h3>
        <button onClick={createSession} style={{ marginRight: 10, padding: '8px 16px' }}>
          Create New Session
        </button>
        <br /><br />
        <input
          type="text"
          placeholder="Or enter session ID"
          value={sessionId}
          onChange={(e) => setSessionId(e.target.value)}
          style={{ width: 350, padding: 8, marginRight: 10 }}
        />
        <button onClick={connectToSession} disabled={connected} style={{ padding: '8px 16px' }}>
          Connect
        </button>
      </div>

      {/* Voting */}
      <div style={{ marginBottom: 20, padding: 15, border: '1px solid #ddd', borderRadius: 4 }}>
        <h3>2. Vote</h3>
        <div style={{ marginBottom: 10 }}>
          <input
            type="text"
            placeholder="Your name"
            value={nameInput}
            onChange={(e) => setNameInput(e.target.value)}
            style={{ width: 150, padding: 8 }}
          />
        </div>
        <div style={{ display: 'flex', gap: 20, alignItems: 'center', position: 'relative', minHeight: 100 }}>
          <button
            onClick={() => castVote('boy')}
            disabled={!connected || hasVoted}
            style={{
              padding: '20px 40px',
              fontSize: 18,
              background: '#4a90d9',
              color: 'white',
              border: 'none',
              borderRadius: 8,
              cursor: connected && !hasVoted ? 'pointer' : 'not-allowed'
            }}
          >
            BOY ({votes.boy})
          </button>
          <button
            onClick={() => castVote('girl')}
            disabled={!connected || hasVoted}
            style={{
              padding: '20px 40px',
              fontSize: 18,
              background: '#e75480',
              color: 'white',
              border: 'none',
              borderRadius: 8,
              cursor: connected && !hasVoted ? 'pointer' : 'not-allowed'
            }}
          >
            GIRL ({votes.girl})
          </button>

          {/* Floating Votes Animation with Names */}
          {floatingVotes.map((v) => (
            <div
              key={v.id}
              style={{
                position: 'absolute',
                left: v.option === 'boy' ? 20 + Math.random() * 80 : 180 + Math.random() * 80,
                bottom: 0,
                animation: 'floatUp 3s ease-out forwards',
                fontSize: 16,
                pointerEvents: 'none',
                textAlign: 'center',
                whiteSpace: 'nowrap'
              }}
            >
              <div style={{ fontSize: 24 }}>{v.option === 'boy' ? '💙' : '💗'}</div>
              <div style={{ fontSize: 12, fontWeight: 'bold' }}>{v.name}</div>
            </div>
          ))}
        </div>
        {hasVoted && <p style={{ color: 'green' }}>You have voted!</p>}
      </div>

      {/* Chat */}
      <div style={{ marginBottom: 20, padding: 15, border: '1px solid #ddd', borderRadius: 4 }}>
        <h3>3. Chat</h3>
        <div style={{ marginBottom: 10 }}>
          <input
            type="text"
            placeholder="Type a message..."
            value={chatInput}
            onChange={(e) => setChatInput(e.target.value)}
            onKeyPress={(e) => e.key === 'Enter' && sendChat()}
            style={{ width: 400, padding: 8, marginRight: 10 }}
          />
          <button onClick={sendChat} disabled={!connected} style={{ padding: '8px 16px' }}>
            Send
          </button>
        </div>

        <div style={{
          height: 200,
          overflowY: 'auto',
          border: '1px solid #eee',
          padding: 10,
          background: '#fafafa'
        }}>
          {messages.length === 0 && <p style={{ color: '#999' }}>No messages yet</p>}
          {messages.map((msg, i) => (
            <div key={i} style={{ marginBottom: 8 }}>
              <strong>{msg.name}:</strong> {msg.message}
              <span style={{ fontSize: 11, color: '#999', marginLeft: 10 }}>
                {new Date(msg.timestamp).toLocaleTimeString()}
              </span>
            </div>
          ))}
        </div>
      </div>

      {/* Instructions */}
      <div style={{ padding: 15, background: '#f5f5f5', borderRadius: 4 }}>
        <h3>How to Test</h3>
        <ol>
          <li>Click "Create New Session" to get a session ID</li>
          <li>Copy the session ID</li>
          <li>Open this page in another browser/tab</li>
          <li>Paste the session ID and click "Connect"</li>
          <li>Enter different names in each browser</li>
          <li>Vote from one browser - see floating heart with name in both!</li>
          <li>Send chat messages - see them appear in both!</li>
        </ol>
        <p><strong>Visitor ID:</strong> <code>{visitorId}</code></p>
      </div>

      {/* CSS for animation */}
      <style>{`
        @keyframes floatUp {
          0% { opacity: 1; transform: translateY(0); }
          100% { opacity: 0; transform: translateY(-150px); }
        }
      `}</style>
    </div>
  );
}

export default App;
```

---

## WebSocket Topics

| Topic | Direction | Purpose |
|-------|-----------|---------|
| `/topic/votes/{sessionId}` | Server → Client | Batched vote counts (every 200ms) |
| `/topic/vote-events/{sessionId}` | Server → Client | **Individual votes with names (instant)** |
| `/topic/chat/{sessionId}` | Server → Client | Chat messages |
| `/topic/vote-response/{sessionId}` | Server → Client | Personal vote confirmation |
| `/app/vote/{sessionId}` | Client → Server | Cast vote |
| `/app/chat/{sessionId}` | Client → Server | Send chat |

---

## Vote Request Format

```json
{
  "option": "boy",
  "visitorId": "uuid-here",
  "name": "Alice"
}
```

**Important:** The `name` field is optional but required for floating vote display.

---

## Run It

```bash
# Terminal 1: Backend
cd /path/to/websockets
docker compose up -d
./mvnw spring-boot:run

# Terminal 2: Test app
cd test-reveal
npm start
```

Open http://localhost:3000 in two browsers and test!

---

## What This Tests

| Feature | How to Test |
|---------|-------------|
| Session creation | Click "Create New Session" |
| WebSocket connection | Click "Connect" - status turns green |
| Live voting | Vote in browser 1, see count update in browser 2 |
| **Floating with name** | Vote as "Alice" - see "💙 Alice" float up in both browsers |
| Duplicate prevention | Try voting twice - should fail |
| Live chat | Send message in browser 1, appears in browser 2 |
| Reconnection | Refresh page, reconnect with same session ID |

---

## Expected Backend Logs

```
Vote received: session=abc-123, visitor=xxx, option=boy, name=Alice
Vote recorded: session=abc-123, visitor=xxx, option=BOY
Broadcast vote event: session=abc-123, name=Alice, option=boy
```

---

## Key Differences from Your Frontend

| Your Frontend | This Test App |
|---------------|---------------|
| Complex state management | Simple useState |
| Multiple components | Single App.tsx |
| Conditional rendering | Always shows everything |
| Animation library | Pure CSS @keyframes |

If this test app works but your frontend doesn't, the issue is in your frontend's state management or rendering logic.
