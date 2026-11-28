export function formatBytes(bytes: number | null | undefined, digits = 1): string {
  if (bytes == null || !Number.isFinite(bytes)) return '–';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = bytes;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(i === 0 ? 0 : digits)} ${units[i]}`;
}

export const formatRate = (bytesPerSecond: number | null | undefined) =>
  bytesPerSecond == null ? '–' : `${formatBytes(bytesPerSecond)}/s`;

export const formatPercent = (v: number | null | undefined, digits = 0) => (v == null ? '–' : `${v.toFixed(digits)}%`);

export const shortSha = (sha: string | null | undefined) => (sha ? sha.slice(0, 7) : '–');

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '–';
  const d = new Date(iso);
  return d.toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '–';
  return new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function relativeTime(iso: string | null | undefined): string {
  if (!iso) return '–';
  const diff = (Date.now() - new Date(iso).getTime()) / 1000;
  const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
  if (Math.abs(diff) < 60) return rtf.format(-Math.round(diff), 'second');
  if (Math.abs(diff) < 3600) return rtf.format(-Math.round(diff / 60), 'minute');
  if (Math.abs(diff) < 86400) return rtf.format(-Math.round(diff / 3600), 'hour');
  return rtf.format(-Math.round(diff / 86400), 'day');
}

export function duration(startIso: string | null | undefined, endIso: string | null | undefined): string {
  if (!startIso) return '–';
  const ms = (endIso ? new Date(endIso).getTime() : Date.now()) - new Date(startIso).getTime();
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
}

export function humanize(value: string | null | undefined): string {
  if (!value) return '–';
  return value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, ' ');
}

const LOCAL_SUFFIXES = ['.local', '.localhost', '.lan', '.home', '.internal', '.home.arpa', '.localdomain'];

/** Whether a hostname names this server on the local network (mirrors PublicUrls on the server). */
export function isLocalHostname(hostname: string): boolean {
  const h = hostname.trim().toLowerCase().replace(/^https?:\/\//, '').replace(/[/:].*$/, '');
  if (!h) return false;
  return h === 'localhost' || !h.includes('.') || /^\d{1,3}(\.\d{1,3}){3}$/.test(h) || LOCAL_SUFFIXES.some((s) => h.endsWith(s));
}

/**
 * The URL an application domain is reached at. Local hostnames get Caddy's host
 * port when it is not 80 (http://devstick.local:8081); public domains never do.
 */
export function appUrl(hostname: string, tls: boolean, httpPort?: number | null): string {
  if (tls) return `https://${hostname}`;
  return `http://${hostname}${httpPort && httpPort !== 80 && isLocalHostname(hostname) ? `:${httpPort}` : ''}`;
}

/** Help text for a local hostname: where it is served and how it resolves. */
export function localHostnameHelp(url: string, hostname: string, mdnsEnabled: boolean): string {
  if (mdnsEnabled && hostname.endsWith('.local')) {
    return `Local hostname: served at ${url}. Published on your network with mDNS while the application runs, so other computers find it with no DNS setup.`;
  }
  return `Local hostname: served at ${url}. Make sure ${hostname} resolves to this server (router DNS or a hosts file)${mdnsEnabled ? '; only .local names are published with mDNS' : ''}.`;
}
