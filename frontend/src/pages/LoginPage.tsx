import { useState, type FormEvent } from 'react';
import { Button, Card, Form, Icon, Input, MessageStrip, PasswordInput, Text } from '@enterprise-ui/react';
import { api, errorMessage } from '../services/api';
import { useSession } from '../state/session';
import { PendingUpdateNotice } from '../components/PlatformUpdateProgress';

/** Sign-in, or first-run creation of the administrator account. */
export function LoginPage({ setup }: { setup: boolean }) {
  const { refresh } = useSession();
  const [username, setUsername] = useState(setup ? 'admin' : '');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (setup && password !== confirm) {
      setError('The passwords do not match.');
      return;
    }
    setBusy(true);
    try {
      if (setup) await api.auth.setup(username, password);
      else await api.auth.login(username, password);
      await refresh();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="sy-auth">
      <Card
        avatar={<Icon name="sap-box" color="var(--eui-color-primary)" size="lg" />}
        title={setup ? 'Welcome to Shipyard' : 'Sign in to Shipyard'}
        subtitle={setup ? 'Create the administrator account to finish setup.' : 'Self-hosted application platform'}
      >
        <Form onSubmit={submit} aria-label={setup ? 'Create administrator' : 'Sign in'}>
          {!setup && <PendingUpdateNotice />}
          {error && <MessageStrip tone="error">{error}</MessageStrip>}
          <Input label="Username" autoComplete="username" required value={username} onChange={(e) => setUsername(e.target.value)} autoFocus={!setup} />
          <PasswordInput label="Password" purpose={setup ? 'new' : 'current'} autoComplete={setup ? 'new-password' : 'current-password'} required
            value={password} onChange={(e) => setPassword(e.target.value)} description={setup ? 'At least 10 characters.' : undefined} />
          {setup && (
            <PasswordInput label="Confirm password" purpose="current" autoComplete="new-password" required value={confirm} onChange={(e) => setConfirm(e.target.value)} />
          )}
          <Button type="submit" variant="primary" fullWidth loading={busy}>{setup ? 'Create administrator' : 'Sign in'}</Button>
          {setup && <Text size="sm" tone="secondary">Passwords are hashed with Argon2id. You can change it later in Settings.</Text>}
        </Form>
      </Card>
    </main>
  );
}
