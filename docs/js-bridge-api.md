# YallaSIP Bridge API — Frontend Integration Guide

**Version**: 1.2.0
**Last updated**: 2026-04-08
**Audience**: Frontend developers integrating with Yalla SIP Phone desktop app

---

## Overview

The Yalla SIP Phone is a desktop application (Kotlin/JVM) that embeds your React dispatcher panel inside a Chromium-based webview (JCEF). The native app handles all SIP/VoIP operations — your web app communicates with it through a JavaScript bridge exposed on `window.YallaSIP`.

```
+---------------------------------------------------+
|  Native Toolbar (56px) — call controls, status     |
+---------------------------------------------------+
|                                                     |
|          Your React App (webview)                   |
|          Full Chromium — no restrictions             |
|                                                     |
|          window.YallaSIP  <-- bridge object          |
|                                                     |
+---------------------------------------------------+
```

**You do NOT need to build any call UI.** The native toolbar handles all call controls (answer, reject, mute, hold, hangup). Your app receives call events and reacts to them (e.g., open customer record when call arrives).

---

## Getting Started

### 1. Wait for the bridge

The native app injects `window.YallaSIP` after your page loads. Poll for it:

```javascript
function waitForBridge() {
  return new Promise((resolve) => {
    if (window.YallaSIP) return resolve(window.YallaSIP);
    const interval = setInterval(() => {
      if (window.YallaSIP) {
        clearInterval(interval);
        resolve(window.YallaSIP);
      }
    }, 50);
  });
}
```

### 2. Signal readiness

Once you detect the bridge, call `ready()`. The native app responds with initialization data and any buffered events that occurred before your app loaded.

```javascript
const bridge = await waitForBridge();
const result = await bridge.ready();

console.log('Agent:', result.data.agent.name);
console.log('Capabilities:', result.data.capabilities);

// Process any events that happened before we were ready
if (result.data.bufferedEvents?.length) {
  result.data.bufferedEvents.forEach(event => {
    const parsed = JSON.parse(event);
    // handle { event: "incomingCall", data: {...} }
  });
}
```

### 3. Subscribe to events

```javascript
// Subscribe — returns an unsubscribe function
const unsub = YallaSIP.on('incomingCall', (data) => {
  console.log('Incoming call from:', data.number);
  openCustomerRecord(data.number);
});

// Later, to unsubscribe:
unsub();
```

### 4. Send commands

All commands return `{ success, data?, error? }`. Always check `success`:

```javascript
const result = await YallaSIP.makeCall('+998901234567');
if (result.success) {
  console.log('Call started:', result.data.callId);
} else {
  console.error('Failed:', result.error.code, result.error.message);
}
```

---

## Response Format

Every command returns a Promise that resolves to:

```typescript
// On success:
{ success: true, data: { ... } | null, error: null }

// On failure:
{ success: false, data: null, error: { code: string, message: string, recoverable: boolean } }
```

**Exceptions**: `getState()` and `getVersion()` auto-unwrap — they resolve directly to the data object (no `success`/`error` wrapper).

---

## Commands Reference

### `makeCall(number)`

Initiate an outbound call.

```javascript
const result = await YallaSIP.makeCall('+998901234567');
// Success: { success: true, data: { callId: "uuid" } }
// Errors: INVALID_NUMBER, ALREADY_IN_CALL, NOT_REGISTERED
```

**Valid formats**: `[+]` followed by digits, `*`, or `#`. Max 20 chars. Examples: `+998901234567`, `101`, `*72#`.

### `answer(callId)`

Answer an incoming call. Get `callId` from the `incomingCall` event.

```javascript
const result = await YallaSIP.answer(callId);
// Success: { success: true, data: null }
// Error: NO_INCOMING_CALL
```

### `reject(callId)`

Reject an incoming call.

```javascript
const result = await YallaSIP.reject(callId);
// Success: { success: true, data: null }
// Error: NO_INCOMING_CALL
```

### `hangup(callId)`

End an active or ringing call.

```javascript
const result = await YallaSIP.hangup(callId);
// Success: { success: true, data: null }
// Error: NO_ACTIVE_CALL
```

### `setMute(callId, muted)`

Explicitly set mute state. **Not a toggle** — avoids race conditions.

```javascript
await YallaSIP.setMute(callId, true);
// { success: true, data: { isMuted: true } }

await YallaSIP.setMute(callId, false);
// { success: true, data: { isMuted: false } }
```

Note: `isMuted` is a proper boolean, not a string.

### `setHold(callId, onHold)`

Explicitly set hold state.

```javascript
await YallaSIP.setHold(callId, true);
// { success: true, data: { isOnHold: true } }
```

### `sendDtmf(callId, digits)`

Send DTMF tones during an active call (e.g., for IVR navigation).

```javascript
const result = await YallaSIP.sendDtmf(callId, '1234#');
// Success: { success: true, data: null }
// Error: NO_ACTIVE_CALL
```

### `transferCall(callId, destination)`

Blind transfer the active call to another number.

```javascript
const result = await YallaSIP.transferCall(callId, '102');
// Success: { success: true, data: { destination: "102" } }
// Error: NO_ACTIVE_CALL
```

### `setAgentStatus(status)`

Change the operator's availability status. Updates both the native toolbar UI and fires an `agentStatusChanged` event.

```javascript
await YallaSIP.setAgentStatus('away');
// { success: true, data: { status: "away" } }
```

Valid statuses: `"ready"`, `"away"`, `"break"`, `"wrap_up"`, `"offline"`.

### `requestLogout()`

Request the native app to perform a full logout. Used when the frontend detects
that the session token has been invalidated (e.g., another operator logged in
with the same PIN).

```javascript
const result = await YallaSIP.requestLogout();
// Success: { success: true, data: null }
```

The native app will: unregister from SIP server, call the backend logout API,
clear the token, and navigate to the login screen.

---

## Queries Reference

Queries auto-unwrap — they resolve directly to the data object.

### `getState()`

Get a full snapshot of current state. Useful for init or recovering after page reload.

```javascript
const state = await YallaSIP.getState();
```

Response:

```typescript
{
  connection: {
    state: "connected" | "reconnecting" | "disconnected",
    attempt: number
  },
  agentStatus: "ready" | "away" | "break" | "wrap_up" | "offline",
  call: null | {
    callId: string,
    number: string,
    direction: "inbound" | "outbound",
    state: "incoming" | "outgoing" | "active" | "on_hold",
    isMuted: boolean,
    isOnHold: boolean,
    duration: number
  },
  accounts: [
    {
      id: string,        // e.g. "1001@sip.yalla.uz"
      name: string,      // e.g. "Operator-1"
      extension: string, // e.g. "1001"
      status: "connected" | "reconnecting" | "disconnected"
    }
  ]
}
```

### `getVersion()`

Get bridge API version and available capabilities.

```javascript
const info = await YallaSIP.getVersion();
// { version: "1.2.0", capabilities: ["call", "agentStatus", "callQuality", "dtmf", "transfer", "multiSip"] }
```

Check capabilities before using optional features:

```javascript
if (info.capabilities.includes('dtmf')) {
  YallaSIP.sendDtmf(callId, '1');
}
```

---

## Events Reference

All events include `seq` (monotonic sequence number) and `timestamp` (epoch ms).

### Call Lifecycle

| Event | When | Key fields |
|-------|------|------------|
| `incomingCall` | Inbound call received | `callId`, `number`, `direction: "inbound"` |
| `outgoingCall` | Outbound call initiated | `callId`, `number`, `direction: "outbound"` |
| `callConnected` | Call answered (either direction) | `callId`, `number`, `direction` |
| `callEnded` | Call terminated | `callId`, `number`, `duration` (seconds), `reason` |
| `callMuteChanged` | Mute toggled | `callId`, `isMuted: boolean` |
| `callHoldChanged` | Hold toggled | `callId`, `isOnHold: boolean` |
| `callRejectedBusy` | Auto-rejected (operator busy) | `number` |

### System

| Event | When | Key fields |
|-------|------|------------|
| `agentStatusChanged` | Status changed (toolbar or API) | `status`, `previousStatus` |
| `connectionChanged` | SIP connection state changed | `state`, `attempt`, `accountId` |
| `accountStatusChanged` | Individual SIP account state changed | `accountId`, `name`, `status` |
| `callQualityUpdate` | Every 5s during active call | `callId`, `quality: "excellent"\|"good"\|"fair"\|"poor"` |
| `themeChanged` | Dark/light mode toggled | `theme: "light"\|"dark"` |
| `error` | Global error | `code`, `message`, `severity` |

### Event Examples

```javascript
YallaSIP.on('incomingCall', (data) => {
  // data = { callId: "uuid", number: "+998901234567", direction: "inbound", seq: 1, timestamp: 1712345678000 }
  openCustomerRecord(data.number);
});

YallaSIP.on('callEnded', (data) => {
  // data = { callId: "uuid", number: "...", direction: "inbound", duration: 45, reason: "hangup", seq: 5, timestamp: ... }
  saveCallRecord(data);
});

YallaSIP.on('agentStatusChanged', (data) => {
  // data = { status: "away", previousStatus: "ready", seq: 3, timestamp: ... }
  syncStatusToBackend(data.status);
});

YallaSIP.on('accountStatusChanged', (data) => {
  // data = { accountId: "1001@sip.yalla.uz", name: "Operator-1", status: "connected", seq: 4, timestamp: ... }
  updateAccountBadge(data.accountId, data.status);
});

YallaSIP.on('connectionChanged', (data) => {
  // data = { state: "disconnected", attempt: 0, accountId: "1001@sip.yalla.uz", seq: 5, timestamp: ... }
  // accountId may be empty for global connection events
  handleConnectionChange(data.state, data.accountId);
});
```

### `callEnded` reasons

| Reason | Meaning |
|--------|---------|
| `hangup` | Normal hangup (either party) |
| `rejected` | Operator rejected incoming call |
| `missed` | Incoming call not answered |
| `busy` | Remote party busy |
| `error` | SIP/network error |

---

## Error Codes

| Code | Meaning | Recoverable |
|------|---------|-------------|
| `ALREADY_IN_CALL` | Active call exists | No — hangup first |
| `NO_ACTIVE_CALL` | No call to operate on | No |
| `NO_INCOMING_CALL` | No ringing call | No |
| `INVALID_NUMBER` | Bad phone number format | Yes — fix input |
| `NOT_REGISTERED` | SIP disconnected | No — wait for reconnect |
| `RATE_LIMITED` | Too many commands | Yes — wait 1-2s |
| `INTERNAL_ERROR` | Unexpected native error | No |

---

## React Integration

```tsx
// hooks/useYallaSIP.ts
import { useEffect, useRef, useState, useCallback } from 'react';

interface CallInfo {
  callId: string;
  number: string;
  direction: 'inbound' | 'outbound';
  state: 'incoming' | 'outgoing' | 'active' | 'on_hold';
  isMuted: boolean;
  isOnHold: boolean;
}

export function useYallaSIP() {
  const bridge = useRef<any>(null);
  const [ready, setReady] = useState(false);
  const [agentName, setAgentName] = useState('');
  const [connection, setConnection] = useState('disconnected');
  const [agentStatus, setAgentStatus] = useState('ready');
  const [call, setCall] = useState<CallInfo | null>(null);
  const [accounts, setAccounts] = useState<Array<{ id: string; name: string; extension: string; status: string }>>([]);

  useEffect(() => {
    let mounted = true;
    const unsubs: Array<() => void> = [];

    async function init() {
      while (!window.YallaSIP && mounted) {
        await new Promise(r => setTimeout(r, 50));
      }
      if (!mounted) return;

      bridge.current = window.YallaSIP;
      const result = await bridge.current.ready();
      if (!mounted) return;

      setAgentName(result.data.agent.name);
      setReady(true);

      // Sync current state
      const state = await bridge.current.getState();
      setConnection(state.connection.state);
      setAgentStatus(state.agentStatus);
      if (state.call) setCall(state.call);
      if (state.accounts) setAccounts(state.accounts);

      // Events
      unsubs.push(bridge.current.on('incomingCall', (d: any) => {
        if (mounted) setCall({ callId: d.callId, number: d.number, direction: 'inbound', state: 'incoming', isMuted: false, isOnHold: false });
      }));
      unsubs.push(bridge.current.on('outgoingCall', (d: any) => {
        if (mounted) setCall({ callId: d.callId, number: d.number, direction: 'outbound', state: 'outgoing', isMuted: false, isOnHold: false });
      }));
      unsubs.push(bridge.current.on('callConnected', () => {
        if (mounted) setCall(prev => prev ? { ...prev, state: 'active' } : null);
      }));
      unsubs.push(bridge.current.on('callEnded', () => {
        if (mounted) setCall(null);
      }));
      unsubs.push(bridge.current.on('callMuteChanged', (d: any) => {
        if (mounted) setCall(prev => prev ? { ...prev, isMuted: d.isMuted } : null);
      }));
      unsubs.push(bridge.current.on('callHoldChanged', (d: any) => {
        if (mounted) setCall(prev => prev ? { ...prev, isOnHold: d.isOnHold, state: d.isOnHold ? 'on_hold' : 'active' } : null);
      }));
      unsubs.push(bridge.current.on('connectionChanged', (d: any) => {
        if (mounted) setConnection(d.state);
      }));
      unsubs.push(bridge.current.on('agentStatusChanged', (d: any) => {
        if (mounted) setAgentStatus(d.status);
      }));
      unsubs.push(bridge.current.on('accountStatusChanged', (d: any) => {
        if (mounted) setAccounts(prev => prev.map(a => a.id === d.accountId ? { ...a, status: d.status } : a));
      }));
    }

    init();
    return () => { mounted = false; unsubs.forEach(fn => fn()); };
  }, []);

  return {
    ready, agentName, connection, agentStatus, call, accounts,
    makeCall: useCallback((n: string) => bridge.current?.makeCall(n), []),
    hangup: useCallback((id: string) => bridge.current?.hangup(id), []),
    answer: useCallback((id: string) => bridge.current?.answer(id), []),
    reject: useCallback((id: string) => bridge.current?.reject(id), []),
    sendDtmf: useCallback((id: string, digits: string) => bridge.current?.sendDtmf(id, digits), []),
    transferCall: useCallback((id: string, dest: string) => bridge.current?.transferCall(id, dest), []),
  };
}
```

---

## Console Testing Cheatsheet

Test the bridge from browser DevTools (Ctrl+Shift+I or port 9222):

```javascript
// Handshake
YallaSIP.ready().then(r => console.log('Init:', r))

// Queries (auto-unwrapped — returns data directly)
YallaSIP.getState().then(s => console.log('State:', s))
YallaSIP.getVersion().then(v => console.log('Version:', v))

// Agent status
YallaSIP.setAgentStatus('away').then(r => console.log(r))
YallaSIP.setAgentStatus('ready').then(r => console.log(r))

// Listen to events
YallaSIP.on('incomingCall', d => console.log('INCOMING:', d))
YallaSIP.on('callConnected', d => console.log('CONNECTED:', d))
YallaSIP.on('callEnded', d => console.log('ENDED:', d))
YallaSIP.on('agentStatusChanged', d => console.log('STATUS:', d))

// Make a call (must be registered)
YallaSIP.makeCall('102').then(r => console.log('Call:', r))

// During active call
YallaSIP.setMute('call-id', true).then(r => console.log(r))
YallaSIP.setHold('call-id', true).then(r => console.log(r))
YallaSIP.sendDtmf('call-id', '123#').then(r => console.log(r))
YallaSIP.hangup('call-id').then(r => console.log(r))
```

---

## Call Simulator (DevTools)

`YallaSIP.simulate` lets you test your app's event handling without a real SIP connection. All simulate methods emit real bridge events that your listeners will receive.

### Individual Steps

```javascript
// Incoming call
YallaSIP.simulate.incoming('+998901234567')  // returns callId

// Outgoing call
YallaSIP.simulate.outgoing('102')

// Answer (connect the call)
YallaSIP.simulate.answer()

// Mute / unmute
YallaSIP.simulate.mute()        // mute on
YallaSIP.simulate.mute(false)   // mute off

// Hold / unhold
YallaSIP.simulate.hold()        // hold on
YallaSIP.simulate.hold(false)   // hold off

// Hangup
YallaSIP.simulate.hangup()          // normal hangup
YallaSIP.simulate.hangup('missed')  // missed call

// Busy rejection (auto-rejected while on another call)
YallaSIP.simulate.busy('+998907654321')

// Connection events
YallaSIP.simulate.disconnect()
YallaSIP.simulate.reconnect(1)   // attempt number
YallaSIP.simulate.connect()
```

### Automated Scenarios

```javascript
// Full call lifecycle: incoming → answer → mute → unmute → hold → unhold → hangup
// Runs automatically with 2s interval between steps
YallaSIP.simulate.callFlow('+998901234567')

// Busy operator day: N random incoming calls with random mute/hold
YallaSIP.simulate.busyDay(5)

// Stop any running scenario
YallaSIP.simulate.stop()

// Check current simulated call state
YallaSIP.simulate.state()  // returns {callId, number, direction, ...} or null
```

### Example: Testing Your React App

```javascript
// 1. Subscribe to events first
YallaSIP.on('incomingCall', d => console.log('UI should show incoming banner for:', d.number))
YallaSIP.on('callConnected', d => console.log('UI should show active call'))
YallaSIP.on('callEnded', d => console.log('UI should clear call, duration:', d.duration))

// 2. Run a scenario
YallaSIP.simulate.callFlow()
// Watch your app react to each step in real time
```

---

## Page Reload Behavior

If your page reloads (F5, navigation, crash):

1. Native detects reload and resets bridge handshake
2. Your page loads fresh, `window.YallaSIP` is re-injected
3. Call `ready()` again — native sends buffered events
4. Call `getState()` to sync with any active call

**Your app must handle being initialized mid-call:**

```javascript
const result = await bridge.ready();
const state = await bridge.getState();

if (state.call) {
  restoreCallUI(state.call);
}
```

---

## Mock Bridge for Development

Use this when developing without the native desktop app:

```typescript
export function createMockBridge() {
  const listeners: Record<string, Set<Function>> = {};

  return {
    ready: async () => ({ success: true, data: {
      version: '1.2.0', capabilities: ['call', 'agentStatus', 'dtmf', 'transfer', 'multiSip'],
      agent: { id: 'mock', name: 'Test Operator' }, bufferedEvents: [],
    }}),
    on: (event: string, fn: Function) => {
      (listeners[event] ??= new Set()).add(fn);
      return () => listeners[event]?.delete(fn);
    },
    off: (event: string, fn: Function) => listeners[event]?.delete(fn),
    makeCall: async (n: string) => ({ success: true, data: { callId: crypto.randomUUID() } }),
    answer: async () => ({ success: true, data: null }),
    reject: async () => ({ success: true, data: null }),
    hangup: async () => ({ success: true, data: null }),
    setMute: async (_: string, m: boolean) => ({ success: true, data: { isMuted: m } }),
    setHold: async (_: string, h: boolean) => ({ success: true, data: { isOnHold: h } }),
    sendDtmf: async () => ({ success: true, data: null }),
    transferCall: async (_: string, d: string) => ({ success: true, data: { destination: d } }),
    setAgentStatus: async (s: string) => ({ success: true, data: { status: s } }),
    getState: async () => ({
      connection: { state: 'connected', attempt: 0 }, agentStatus: 'ready', call: null,
      accounts: [{ id: '1001@sip.mock', name: 'Test-1', extension: '1001', status: 'connected' }],
    }),
    getVersion: async () => ({ version: '1.2.0', capabilities: ['call', 'agentStatus', 'dtmf', 'transfer', 'multiSip'] }),
    // Test helper — fire events manually
    simulate: (event: string, data: any) => listeners[event]?.forEach(fn => fn(data)),
  };
}

// Usage: window.YallaSIP = createMockBridge();
```

---

## Security Notes

- Phone numbers in events are PII — do not log to third-party services
- Bridge only available in main frame — iframes cannot access it
- All commands rate-limited on native side (returns `RATE_LIMITED`)
- Server IPs never exposed through the bridge
- Raw call metrics (MOS, jitter) stay native-side
