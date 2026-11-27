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

/** Alternative event source (the GitHub Pages demo's simulated backend). */
export interface EventTransport {
  subscribe(applicationId: string | null, listener: Listener): () => void;
  streamLogs(applicationId: string, tail: number, onLine: (line: string) => void): () => void;
}

let transport: EventTransport | null = null;

export function setEventTransport(t: EventTransport) {
  transport = t;
  setConnected(true);
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
  if (transport) return transport.subscribe(applicationId, listener);
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

/**
 * Follows an application's container logs. Returns a function that stops
 * the stream. {@code onState} reports connection state for the UI.
 */
export function streamApplicationLogs(
  applicationId: string,
  tail: number,
  onLine: (line: string) => void,
  onState: (state: 'connecting' | 'streaming' | 'error') => void,
): () => void {
  if (transport) {
    onState('streaming');
    return transport.streamLogs(applicationId, tail, onLine);
  }
  const source = new EventSource(`/api/applications/${encodeURIComponent(applicationId)}/logs/stream?tail=${tail}`, { withCredentials: true });
  source.addEventListener('log', (e) => {
    onState('streaming');
    onLine((e as MessageEvent).data);
  });
  source.onopen = () => onState('streaming');
  source.onerror = () => onState(source.readyState === EventSource.CLOSED ? 'error' : 'connecting');
  return () => source.close();
}
