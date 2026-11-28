/** Parses one container path per line (blank lines and # comments ignored). */
export function parseVolumes(text: string): string[] {
  return text.split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
}

/** Returns an error message for the first invalid path, or null. Mirrors the server's rules. */
export function volumeError(paths: string[]): string | null {
  for (const p of paths) {
    if (!p.startsWith('/')) return `Volume path must be absolute (start with /): ${p}`;
    if (p.replace(/\/+$/, '') === '') return 'The container root directory cannot be a volume.';
    if (p.includes('..') || /[\s:,]/.test(p)) return `Invalid volume path: ${p}`;
  }
  return paths.length > 10 ? 'At most 10 volumes per application.' : null;
}

/** Docker volume name Shipyard uses for a path (see the server's Volumes class). */
export function volumeName(slug: string, path: string): string {
  let s = path.toLowerCase().replace(/\/+$/, '').replace(/[^a-z0-9_.-]+/g, '-').replace(/-{2,}/g, '-').replace(/^[-.]+|[-.]+$/g, '');
  if (!s) s = 'data';
  return `shipyard-${slug}-${s}`;
}
