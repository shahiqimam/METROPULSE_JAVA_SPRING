import { Injectable, inject, signal } from '@angular/core';
import { Client, IMessage } from '@stomp/stompjs';
import { AuthService } from '../auth/auth.service';

export type RealtimeState = 'disconnected' | 'connecting' | 'live';

type Handler = (payload: unknown) => void;

/**
 * The realtime channel.
 *
 * <h2>REST is the truth; this is the update</h2>
 *
 * <p>Callers fetch a baseline over REST and then subscribe here. They never build state from deltas
 * alone, because a client that has only seen deltas cannot know what it missed while it was
 * disconnected. On reconnect, `onReconnect` fires so the caller refetches the baseline and resumes.
 *
 * <p>That is also why losing a message is acceptable: nothing is acknowledged and nothing is
 * replayed. The worst case is a few seconds of staleness until the next update or the next poll.
 *
 * <p>Polling stays in place as a fallback. If the socket never connects — a proxy that will not
 * upgrade, a corporate network, a bug here — the dashboard keeps working, just less promptly.
 */
@Injectable({ providedIn: 'root' })
export class RealtimeService {
  private readonly auth = inject(AuthService);

  private client: Client | null = null;
  private readonly handlers = new Map<string, Handler>();
  private hasConnectedBefore = false;
  private reconnectHandler: (() => void) | null = null;

  readonly state = signal<RealtimeState>('disconnected');

  /** Called after a reconnect, so the caller can refetch its baseline. */
  onReconnect(handler: () => void): void {
    this.reconnectHandler = handler;
  }

  subscribe(topic: string, handler: Handler): void {
    this.handlers.set(topic, handler);
    if (this.client?.connected) {
      this.bind(topic, handler);
    }
  }

  connect(): void {
    if (this.client || !this.auth.accessToken()) {
      return;
    }

    this.state.set('connecting');

    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    this.client = new Client({
      brokerURL: `${protocol}://${window.location.host}/ws`,
      // The token travels in the CONNECT frame: a browser cannot set headers on the handshake.
      connectHeaders: { Authorization: `Bearer ${this.auth.accessToken()}` },
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        this.state.set('live');
        this.handlers.forEach((handler, topic) => this.bind(topic, handler));

        if (this.hasConnectedBefore) {
          // Whatever happened while we were away, we did not see it.
          this.reconnectHandler?.();
        }
        this.hasConnectedBefore = true;
      },
      onWebSocketClose: () => this.state.set('connecting'),
      onStompError: () => this.state.set('disconnected'),
      // An expired token makes the CONNECT fail; giving up lets polling carry the dashboard rather
      // than reconnecting forever with a credential that will not work.
      onDisconnect: () => this.state.set('disconnected')
    });

    this.client.activate();
  }

  disconnect(): void {
    this.client?.deactivate();
    this.client = null;
    this.hasConnectedBefore = false;
    this.state.set('disconnected');
  }

  private bind(topic: string, handler: Handler): void {
    this.client?.subscribe(topic, (message: IMessage) => {
      try {
        handler(JSON.parse(message.body));
      } catch {
        // A malformed frame is not worth breaking the dashboard over; the next poll corrects it.
      }
    });
  }
}
