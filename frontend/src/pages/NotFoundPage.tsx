import { useNavigate } from 'react-router-dom';
import { Button, EmptyState, Page } from '@enterprise-ui/react';

export function NotFoundPage() {
  const navigate = useNavigate();
  return (
    <Page title="Page not found">
      <EmptyState icon="error" title="This page does not exist" actions={<Button variant="primary" onClick={() => navigate('/')}>Go to dashboard</Button>} />
    </Page>
  );
}
