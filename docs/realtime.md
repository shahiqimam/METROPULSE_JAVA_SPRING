# Realtime

## REST for the baseline, WebSocket for what changed

The dashboard fetches its picture over REST and then subscribes for updates. It never builds state
from the socket alone.

That is the whole design. A client that has only ever seen deltas cannot know what it missed while it
was disconnected — and it *will* be disconnected, because networks are like that. After a reconnect
the client refetches the baseline and resumes.

The consequence is liberating: **the socket is allowed to drop messages.** Nothing is acknowledged,
nothing is replayed, and no state depends on a delta arriving. The worst case is a few seconds of
staleness until the next update or the next poll.

Polling stays in place as a fallback. If the socket never connects — a proxy that will not upgrade, a
corporate network, a bug — the dashboard still works, just less promptly. The header shows which
channel is in use: `STREAMING` or `POLLING`.

## Topics

```text
/topic/vehicles   current fleet state
/topic/alerts     the live alert list
/topic/headway    spacing conditions
```

Summaries, not raw telemetry. Sending every observation to every browser would be tens of messages a
second per client to say what one message says, and the control centre's question is "what is the
network doing", not "what did BUS-042 report at 10:15:02".

## Only broadcast when something changed

`RealtimeBroadcaster` compares what it last sent with what is current and stays quiet if they match.
A control-room screen that redraws every two seconds whether or not anything moved is both wasteful
and harder to watch: a change on screen should mean a change in the network.

An empty list is still a change worth sending — without it, a screen would keep showing an alert that
has closed.

## Authentication

The socket needs its own check, and this is easy to get wrong.

The HTTP filter chain runs on the handshake, but **a browser cannot set an `Authorization` header on
a WebSocket handshake**. So the security rules let `/ws` through, and the token travels in the STOMP
`CONNECT` frame, where `WebSocketAuthenticationInterceptor` verifies it and attaches the
authentication to the session.

Letting the handshake through is not the same as letting the connection through. Without the
interceptor, an unauthenticated client could open a socket and receive every broadcast — a leak no
amount of REST authorisation would catch, because that data never travels over REST. Two tests assert
it: a client with no token and a client with a garbage token are both refused.

## Proxying

nginx must be told to upgrade the connection:

```nginx
location /ws {
    proxy_pass http://backend:8080/ws;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;
}
```

Two details that cost time if missed: the location is `/ws`, not `/ws/`, because the endpoint is
exactly `/ws`; and the read timeout has to be long, or a socket that is merely quiet gets closed.

## Limitations

- The broker is in-memory. With several backend instances, each would broadcast only to its own
  subscribers. That is the point at which a broker relay becomes necessary.
- Subscriptions are network-wide. A controller watching one route still receives everything, which is
  fine for four vehicles and would not be for four hundred: scoping to `/topic/routes/{code}` is the
  obvious next step.
- Changes are detected by polling the database every two seconds, not by the projection pushing them.
  Driving broadcasts from the Kafka consumer would be more direct and is the natural evolution.
- An expired access token fails the CONNECT. The client stops retrying and falls back to polling
  rather than reconnecting forever with a credential that will not work.
