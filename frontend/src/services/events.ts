import type { PlatformEvent } from './types';

type Listener = (event: PlatformEvent) => void;

/** Event names the server emits (see backend EventTypes). */
export const EVENT_TYPES = [
  'WebhookReceived', 'WebhookProcessed', 'DeploymentRequested', 'DeploymentStatusChanged', 'BuildStarted', 'BuildLog',
  'BuildCompleted', 'DeploymentStarted', 'DeploymentLog', 'DeploymentHealthy', 'DeploymentFailed', 'DeploymentRolledBack',
  'ApplicationStarted', 'ApplicationStopped', 'ApplicationCrashed', 'ApplicationUpdated', 'MetricsUpdated',
] as const;

interface Stream {
  source: EventSource;
  listeners: Set<Listener>;
}

const streams = new Map<string, Stream>();
const connectionListeners = new Set<(connected: boolean) => void>();
let connected = false;

function setConnected(value: boolean) {
  if (connected === value) return;
  connected = value;
  connectionListeners.forEach((l) => l(value));
}

/**
 * Subscribes to the platform-wide stream (applicationId = null) or one
 * application's stream. Streams are shared and close when the last
 * listener leaves. EventSource reconnects automatically.
 */
export function subscribe(applicationId: string | null, listener: Listener): () => void {
  const key = applicationId ?? '*';
  let stream = streams.get(key);
  if (!stream) {
    const url = applicationId ? `/api/applications/${encodeURIComponent(applicationId)}/events` : '/api/events';
    const source = new EventSource(url, { withCredentials: true });
    const s: Stream = { source, listeners: new Set() };
    const dispatch = (e: MessageEvent) => {
      try {
        const event = JSON.parse(e.data) as PlatformEvent;
        s.listeners.forEach((l) => l(event));
      } catch {
        // ignore malformed
      }
    };
    EVENT_TYPES.forEach((t) => source.addEventListener(t, dispatch as EventListener));
    if (key === '*') {
      source.addEventListener('ready', () => setConnected(true));
      source.onerror = () => setConnected(source.readyState === EventSource.OPEN);
    }
    streams.set(key, s);
    stream = s;
  }
  stream.listeners.add(listener);
  return () => {
    const s = streams.get(key);
    if (!s) return;
    s.listeners.delete(listener);
    if (s.listeners.size === 0) {
      s.source.close();
      streams.delete(key);
      if (key === '*') setConnected(false);
    }
  };
}

export function onConnectionChange(listener: (connected: boolean) => void): () => void {
  connectionListeners.add(listener);
  listener(connected);
  return () => connectionListeners.delete(listener);
}

export function closeAllStreams() {
  streams.forEach((s) => s.source.close());
  streams.clear();
  setConnected(false);
}
