import { useMatch, useNavigate } from 'react-router-dom';
import { Button, Icon, MessageStrip, useToast } from '@enterprise-ui/react';
import { demoApplications, simulatePush } from './demoBackend';

/** Banner shown on the GitHub Pages demo, with shortcuts to trigger the simulated pipeline. */
export function DemoBanner() {
  const navigate = useNavigate();
  const toast = useToast();
  const match = useMatch('/applications/:id/*');
  const appId = match?.params.id && match.params.id !== 'new' ? match.params.id : null;

  const push = (failing: boolean) => {
    const target = appId ?? demoApplications()[0].id;
    try {
      const d = simulatePush(target, failing);
      if (!d) {
        toast.show({ title: 'Push recorded, not deployed', description: 'Automatic deployment is off for this application.', tone: 'information' });
        return;
      }
      toast.show({ title: `Webhook received: deployment #${d.number} queued`, description: d.commitMessage ?? undefined, tone: 'information' });
      navigate(`/applications/${target}/deployments/${d.id}`);
    } catch {
      toast.show({ title: 'Could not simulate a push', tone: 'error' });
    }
  };

  return (
    <div style={{ padding: 'var(--eui-space-2) var(--eui-space-4) 0' }}>
      <MessageStrip
        tone="information"
        title="Live demo"
      >
        Everything here runs in your browser against simulated data; reload to reset.
        {appId ? ' Pushes go to this application.' : ' Pushes go to the first application, or open one to target it.'}
        <div className="sy-row" style={{ marginTop: 'var(--eui-space-2)' }}>
          <Button size="sm" icon={<Icon name="upload" />} onClick={() => push(false)}>Simulate git push</Button>
          <Button size="sm" variant="tertiary" onClick={() => push(true)}>Simulate a failing push</Button>
          <Button size="sm" variant="tertiary" onClick={() => { window.location.href = '../'; }}>Docs</Button>
        </div>
      </MessageStrip>
    </div>
  );
}
