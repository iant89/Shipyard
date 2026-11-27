import { useCallback, useEffect, useRef, useState } from 'react';
import { errorMessage } from '../services/api';
import { subscribe } from '../services/events';
import type { PlatformEvent } from '../services/types';

export interface Resource<T> {
  data: T | undefined;
  error: string | null;
  loading: boolean;
  reload: () => void;
  setData: (data: T) => void;
}

interface Options {
  /** Refetch when one of these events arrives on the stream. */
  refreshOn?: readonly string[];
  /** Application stream to listen to; null = platform-wide stream; undefined = no live refresh. */
  stream?: string | null;
  /** Extra filter for events. */
  filter?: (e: PlatformEvent) => boolean;
  /** Also poll at this interval (ms). */
  pollMs?: number;
}

/**
 * Server-state hook: fetches, keeps the last good data while refetching, and
 * refreshes on matching real-time events (debounced).
 */
export function useResource<T>(fetcher: () => Promise<T>, deps: unknown[], options: Options = {}): Resource<T> {
  const [data, setData] = useState<T | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  const seq = useRef(0);

  const load = useCallback(() => {
    const n = ++seq.current;
    setLoading(true);
    fetcherRef.current()
      .then((d) => {
        if (n !== seq.current) return;
        setData(d);
        setError(null);
      })
      .catch((e) => {
        if (n !== seq.current) return;
        setError(errorMessage(e));
      })
      .finally(() => {
        if (n === seq.current) setLoading(false);
      });
  }, []);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(load, deps);

  const { refreshOn, stream, filter, pollMs } = options;
  const filterRef = useRef(filter);
  filterRef.current = filter;
  const refreshKey = refreshOn?.join(',');

  useEffect(() => {
    if (!refreshOn || stream === undefined) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const unsubscribe = subscribe(stream, (e) => {
      if (!refreshOn.includes(e.type)) return;
      if (filterRef.current && !filterRef.current(e)) return;
      clearTimeout(timer);
      timer = setTimeout(load, 300);
    });
    return () => {
      clearTimeout(timer);
      unsubscribe();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshKey, stream, load]);

  useEffect(() => {
    if (!pollMs) return;
    const t = setInterval(load, pollMs);
    return () => clearInterval(t);
  }, [pollMs, load]);

  return { data, error, loading: loading && data === undefined, reload: load, setData };
}
