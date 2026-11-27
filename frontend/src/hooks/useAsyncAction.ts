import { useCallback, useState } from 'react';
import { useToast } from '@enterprise-ui/react';
import { errorMessage } from '../services/api';

/** Runs an action with a busy flag and success/error toasts. */
export function useAsyncAction() {
  const toast = useToast();
  const [busy, setBusy] = useState<string | null>(null);
  const run = useCallback(
    async <T,>(key: string, action: () => Promise<T>, success?: string | ((result: T) => string)): Promise<T | undefined> => {
      setBusy(key);
      try {
        const result = await action();
        if (success) toast.show({ title: typeof success === 'function' ? success(result) : success, tone: 'success' });
        return result;
      } catch (e) {
        toast.show({ title: 'Action failed', description: errorMessage(e), tone: 'error' });
        return undefined;
      } finally {
        setBusy(null);
      }
    },
    [toast],
  );
  return { busy, run };
}
