import { Navigate, Route, Routes, useParams } from 'react-router-dom';
import { DashboardPage } from '../pages/DashboardPage';
import { ApplicationsPage } from '../pages/ApplicationsPage';
import { AddApplicationPage } from '../pages/AddApplicationPage';
import { ApplicationPage } from '../pages/ApplicationPage';
import { DeploymentPage } from '../pages/DeploymentPage';
import { DeploymentsPage } from '../pages/DeploymentsPage';
import { InfrastructurePage } from '../pages/InfrastructurePage';
import { ActivityPage } from '../pages/ActivityPage';
import { SettingsPage } from '../pages/SettingsPage';
import { PlatformUpdatePage } from '../pages/PlatformUpdatePage';
import { NotFoundPage } from '../pages/NotFoundPage';
import { IdePage } from '../pages/IdePage';

function ApplicationIdeRoute() {
  const { id = '' } = useParams();
  return <IdePage key={id} />;
}

/** Routes (spec section 69). Application sub-pages are tabs of the application object page. */
export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<DashboardPage />} />
      <Route path="/applications" element={<ApplicationsPage />} />
      <Route path="/applications/new" element={<AddApplicationPage />} />
      <Route path="/applications/:id/deployments/:deploymentId" element={<DeploymentPage />} />
      <Route path="/applications/:id/ide" element={<ApplicationIdeRoute />} />
      <Route path="/applications/:id/:tab?" element={<ApplicationPage />} />
      <Route path="/deployments" element={<DeploymentsPage />} />
      <Route path="/infrastructure" element={<InfrastructurePage />} />
      <Route path="/activity" element={<ActivityPage />} />
      <Route path="/settings" element={<SettingsPage />} />
      <Route path="/settings/update" element={<PlatformUpdatePage />} />
      <Route path="/login" element={<Navigate to="/" replace />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
