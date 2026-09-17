# 0006 - REST Baseline, WebSocket Deltas

## Context

The control centre needs to show what is happening now. Polling every few seconds is simple but
always slightly wrong; a push channel is prompt but unreliable in ways that matter — sockets drop,
proxies refuse to upgrade, and a client cannot know what it missed while it was away.

## Decision

REST is the source of truth for state; the WebSocket carries changes.

The client fetches a baseline over REST, subscribes for updates, and on reconnect **refetches the
baseline** rather than trusting what it holds. Polling is kept as a fallback, and the UI says which
channel is live.

Broadcasts go out only when the picture actually changed.

## Alternatives

- **Polling alone.** Works, and is what this replaced. Every screen is up to a poll interval stale,
  and shortening the interval trades staleness for load.
- **WebSocket alone.** Prompt, but a client that has only seen deltas cannot recover from a
  disconnection without a baseline to fetch — which means building one anyway.
- **Server-sent events.** Enough for one-way updates and simpler than STOMP. STOMP was chosen for its
  topic model, which is what makes per-route scoping a small change later.
- **Pushing raw telemetry.** Tens of messages a second per client to say what one summary says.

## Consequences

- The socket is **allowed** to drop messages. Nothing is acknowledged, nothing is replayed, and no
  state depends on a delta arriving; the worst case is a few seconds of staleness.
- A proxy that will not upgrade degrades the experience instead of breaking it.
- The socket needs its own authentication, because a browser cannot set an `Authorization` header on
  a handshake — the token travels in the STOMP CONNECT frame.
- The broker is in-memory, so a second backend instance would broadcast only to its own subscribers.
  That is the point at which a broker relay becomes necessary.
- Subscriptions are network-wide. Fine for four vehicles; scoping to `/topic/routes/{code}` is the
  obvious next step for four hundred.
