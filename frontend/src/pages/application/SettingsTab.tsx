import { useEffect, useState } from 'react';
import { Bar, Button, Card, DescriptionList, Form, FormGroup, Input, MessageStrip, NumberInput, PasswordInput, Select, Switch, TextArea } from '@enterprise-ui/react';
import { api, errorMessage, type ServiceSettings } from '../../services/api';
import { parseVolumes, volumeError, volumeName } from '../../services/volumes';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import type { ApplicationDetail, DetectionResult } from '../../services/types';
import { humanize } from '../../services/format';

interface Values {
  name: string;
  description: string;
  repository: string;
  branch: string;
  token: string;
  clearToken: boolean;
  autoDeploy: boolean;
  autoRollback: boolean;
  cancelStaleBuilds: boolean;
  autoUpdate: boolean;
  projectType: string;
  buildStrategy: string;
  rootDirectory: string;
  installCommand: string;
  buildCommand: string;
  testCommand: string;
  startCommand: string;
  outputDirectory: string;
  runtimeImage: string;
  internalPort: number | null;
  healthCheckType: string;
  healthCheckPath: string;
  healthCheckTimeoutSeconds: number | null;
  cpuLimit: number | null;
  memoryLimitMb: number | null;
  restartPolicy: string;
  volumes: string;
}

const fromDetail = (d: ApplicationDetail): Values => ({
  name: d.summary.name,
  description: d.summary.description ?? '',
  repository: d.summary.repository?.url ?? '',
  branch: d.summary.repository?.branch ?? 'main',
  token: '',
  clearToken: false,
  autoDeploy: d.summary.autoDeploy,
  autoRollback: d.autoRollback,
  cancelStaleBuilds: d.cancelStaleBuilds,
  autoUpdate: d.summary.autoUpdate,
  projectType: d.service.projectType ?? '',
  buildStrategy: d.service.buildStrategy ?? '',
  rootDirectory: d.service.rootDirectory === '.' ? '' : d.service.rootDirectory,
  installCommand: d.service.installCommand ?? '',
  buildCommand: d.service.buildCommand ?? '',
  testCommand: d.service.testCommand ?? '',
  startCommand: d.service.startCommand ?? '',
  outputDirectory: d.service.outputDirectory ?? '',
  runtimeImage: d.service.runtimeImage ?? '',
  internalPort: d.service.internalPort,
  healthCheckType: d.service.healthCheckType,
  healthCheckPath: d.service.healthCheckPath,
  healthCheckTimeoutSeconds: d.service.healthCheckTimeoutSeconds,
  cpuLimit: d.service.cpuLimit,
  memoryLimitMb: d.service.memoryLimitMb,
  restartPolicy: d.service.restartPolicy,
  volumes: (d.service.volumes ?? []).join('\n'),
});

const nz = (s: string) => (s.trim() ? s.trim() : null);

const STRATEGIES = ['DOCKERFILE', 'STATIC_FILES', 'NODE_STATIC', 'NODE_SERVER', 'BUN_SERVER', 'PYTHON', 'GO', 'RUST', 'MAVEN', 'GRADLE', 'DOTNET', 'PHP'];

/** Persistent application configuration: repository, build, runtime, health, resources, automation. */
export function SettingsTab({ detail, onSaved }: { detail: ApplicationDetail; onSaved: (d: ApplicationDetail) => void }) {
  const [v, setV] = useState<Values>(() => fromDetail(detail));
  const [detection, setDetection] = useState<DetectionResult | null>(null);
  const [detectError, setDetectError] = useState<string | null>(null);
  const { busy, run } = useAsyncAction();
  const set = <K extends keyof Values>(k: K, value: Values[K]) => setV((cur) => ({ ...cur, [k]: value }));

  useEffect(() => setV(fromDetail(detail)), [detail]);

  const volumePaths = parseVolumes(v.volumes);
  const volumesInvalid = volumeError(volumePaths);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    if (volumesInvalid) return;
    const service: ServiceSettings = {
      projectType: nz(v.projectType),
      buildStrategy: nz(v.buildStrategy),
      rootDirectory: nz(v.rootDirectory) ?? '.',
      installCommand: nz(v.installCommand),
      buildCommand: nz(v.buildCommand),
      testCommand: nz(v.testCommand),
      startCommand: nz(v.startCommand),
      outputDirectory: nz(v.outputDirectory),
      runtimeImage: nz(v.runtimeImage),
      internalPort: v.internalPort,
      healthCheckType: v.healthCheckType,
      healthCheckPath: v.healthCheckPath || '/',
      healthCheckTimeoutSeconds: v.healthCheckTimeoutSeconds,
      cpuLimit: v.cpuLimit,
      memoryLimitMb: v.memoryLimitMb,
      restartPolicy: v.restartPolicy,
      volumes: parseVolumes(v.volumes),
    };
    const r = await run('save', () => api.applications.update(detail.summary.id, {
      name: v.name, description: v.description, repository: v.repository, branch: v.branch,
      accessToken: v.token || undefined, clearAccessToken: v.clearToken,
      autoDeploy: v.autoDeploy, autoRollback: v.autoRollback, cancelStaleBuilds: v.cancelStaleBuilds, autoUpdate: v.autoUpdate, service,
    }), 'Settings saved. They apply to the next deployment.');
    if (r) onSaved(r);
  };

  const redetect = async () => {
    setDetectError(null);
    setDetection(null);
    try {
      setDetection(await api.applications.detect(detail.summary.id));
    } catch (e) {
      setDetectError(errorMessage(e));
    }
  };

  return (
    <Form onSubmit={save} aria-label="Application settings">
      <div className="sy-stack">
        <Card title="General">
          <FormGroup columns={2}>
            <Input label="Name" required value={v.name} onChange={(e) => set('name', e.target.value)} />
            <TextArea label="Description" rows={2} value={v.description} onChange={(e) => set('description', e.target.value)} />
          </FormGroup>
        </Card>

        <Card title="Repository">
          <FormGroup columns={2}>
            <Input label="Repository URL" required value={v.repository} onChange={(e) => set('repository', e.target.value)} />
            <Input label="Deployment branch" required value={v.branch} onChange={(e) => set('branch', e.target.value)}
              description="Only pushes to this branch trigger automatic deployments." />
            <PasswordInput label="Access token" value={v.token} onChange={(e) => set('token', e.target.value)} autoComplete="off"
              description={detail.summary.repository?.privateRepository ? 'A token is stored. Enter a new one to replace it.' : 'Only needed for private repositories.'} />
            {detail.summary.repository?.privateRepository && (
              <Switch label="Remove stored token" checked={v.clearToken} onChange={(c) => set('clearToken', c)} />
            )}
            <Input label="Root directory" value={v.rootDirectory} placeholder="." onChange={(e) => set('rootDirectory', e.target.value)} />
          </FormGroup>
        </Card>

        <Card title="Build" subtitle="Empty fields use automatic detection (Dockerfile → platform.yml → framework)."
          headerActions={<Button size="sm" onClick={redetect}>Run detection</Button>}>
          {detectError && <MessageStrip tone="error" title="Detection failed">{detectError}</MessageStrip>}
          {detection && (
            <MessageStrip tone="information" title={`Detected ${detection.framework ?? humanize(detection.buildStrategy)}`} dismissible onDismiss={() => setDetection(null)}>
              <DescriptionList columns={3} items={[
                { term: 'Strategy', description: humanize(detection.buildStrategy) },
                { term: 'Build', description: detection.buildCommand ?? '–' },
                { term: 'Start', description: detection.startCommand ?? '–' },
                { term: 'Output', description: detection.outputDirectory ?? '–' },
                { term: 'Port', description: String(detection.port ?? '–') },
                { term: 'Source', description: humanize(detection.source) },
              ]} />
            </MessageStrip>
          )}
          <FormGroup columns={2}>
            <Select label="Application type" placeholder="Detect" value={v.projectType || null}
              options={[{ value: 'STATIC', label: 'Static' }, { value: 'SERVER', label: 'Server' }]} onChange={(x) => set('projectType', x ?? '')} />
            <Select label="Build strategy" placeholder="Detect" value={v.buildStrategy || null}
              options={STRATEGIES.map((s) => ({ value: s, label: humanize(s) }))} onChange={(x) => set('buildStrategy', x ?? '')} />
            <Input label="Install command" value={v.installCommand} onChange={(e) => set('installCommand', e.target.value)} />
            <Input label="Build command" value={v.buildCommand} onChange={(e) => set('buildCommand', e.target.value)} />
            <Input label="Test command" value={v.testCommand} onChange={(e) => set('testCommand', e.target.value)} description="Runs in the build container; failures stop the deployment." />
            <Input label="Output directory" value={v.outputDirectory} onChange={(e) => set('outputDirectory', e.target.value)} description="Static applications only." />
          </FormGroup>
        </Card>

        <Card title="Runtime">
          <FormGroup columns={2}>
            <Input label="Start command" value={v.startCommand} onChange={(e) => set('startCommand', e.target.value)} />
            <NumberInput label="Internal port" min={1} max={65535} value={v.internalPort} onChange={(x) => set('internalPort', x)} />
            <Input label="Runtime image" value={v.runtimeImage} onChange={(e) => set('runtimeImage', e.target.value)} placeholder="node:lts-alpine" />
            <Select label="Restart policy" value={v.restartPolicy} onChange={(x) => x && set('restartPolicy', x)}
              options={[{ value: 'unless-stopped', label: 'Unless stopped' }, { value: 'always', label: 'Always' }, { value: 'on-failure:5', label: 'On failure (5 tries)' }, { value: 'no', label: 'Never' }]} />
            <NumberInput label="CPU limit" unit="cores" min={0.1} max={64} step={0.5} precision={1} value={v.cpuLimit} onChange={(x) => set('cpuLimit', x)} />
            <NumberInput label="Memory limit" unit="MB" min={32} max={262144} step={128} value={v.memoryLimitMb} onChange={(x) => set('memoryLimitMb', x)} />
          </FormGroup>
        </Card>

        <Card title="Volumes" subtitle="Data in these container paths is kept across deployments, rollbacks and restarts.">
          <FormGroup columns={1}>
            <TextArea label="Container paths" value={v.volumes} onChange={(e) => set('volumes', e.target.value)} rows={3} placeholder="/data"
              className="sy-mono" error={volumesInvalid ?? undefined}
              description={volumePaths.length && !volumesInvalid
                ? `One path per line. Stored in Docker volumes ${volumePaths.map((p) => volumeName(detail.summary.slug, p)).join(', ')}; deleting the application deletes them.`
                : 'One path per line, e.g. /data. Each path gets a named Docker volume; deleting the application deletes it.'} />
          </FormGroup>
        </Card>

        <Card title="Health check">
          <FormGroup columns={3}>
            <Select label="Type" value={v.healthCheckType} onChange={(x) => x && set('healthCheckType', x)}
              options={[{ value: 'HTTP', label: 'HTTP' }, { value: 'TCP', label: 'TCP port' }, { value: 'PROCESS', label: 'Process running' }, { value: 'NONE', label: 'None' }]} />
            <Input label="Path" value={v.healthCheckPath} disabled={v.healthCheckType !== 'HTTP'} onChange={(e) => set('healthCheckPath', e.target.value)} />
            <NumberInput label="Timeout" unit="s" min={5} max={900} value={v.healthCheckTimeoutSeconds} onChange={(x) => set('healthCheckTimeoutSeconds', x)} />
          </FormGroup>
        </Card>

        <Card title="Automation">
          <FormGroup columns={2}>
            <Switch label="Automatic deployment" checked={v.autoDeploy} onChange={(c) => set('autoDeploy', c)} description="Deploy on push through the GitHub webhook." />
            <Switch label="Automatic updates" checked={v.autoUpdate} onChange={(c) => set('autoUpdate', c)}
              description="Check the branch every 90 seconds and deploy new commits. Works without a webhook; a commit that fails is not retried." />
            <Switch label="Automatic rollback" checked={v.autoRollback} onChange={(c) => set('autoRollback', c)}
              description="Remove a failed new container immediately. Off keeps it stopped for inspection. The current deployment stays online either way." />
            <Switch label="Cancel stale builds" checked={v.cancelStaleBuilds} onChange={(c) => set('cancelStaleBuilds', c)} description="A newer push cancels deployments that are still queued." />
          </FormGroup>
        </Card>

        <Bar design="floating-footer" endContent={
          <>
            <Button onClick={() => setV(fromDetail(detail))}>Reset</Button>
            <Button type="submit" variant="primary" loading={busy === 'save'}>Save settings</Button>
          </>
        } />
      </div>
    </Form>
  );
}
