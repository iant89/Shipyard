import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  BusyIndicator, Checkbox, DescriptionList, FormGroup, Input, MessageStrip, NumberInput, Page, PasswordInput, Select, Switch, Text,
  TextArea, Wizard, useToast,
} from '@enterprise-ui/react';
import { api, errorMessage, type ServiceSettings } from '../services/api';
import type { DetectionPreview } from '../services/types';
import { humanize, shortSha } from '../services/format';
import { appHref } from '../app/platformMode';

interface Draft {
  name: string;
  description: string;
  repository: string;
  branch: string;
  privateRepo: boolean;
  token: string;
  rootDirectory: string;
  // overrides (empty = use detection)
  projectType: '' | 'STATIC' | 'SERVER';
  installCommand: string;
  buildCommand: string;
  testCommand: string;
  outputDirectory: string;
  startCommand: string;
  port: number | null;
  // deployment
  domain: string;
  autoDeploy: boolean;
  autoUpdate: boolean;
  healthCheckType: string;
  healthCheckPath: string;
  cpuLimit: number | null;
  memoryLimitMb: number | null;
  env: string;
  envSecret: boolean;
}

const initial: Draft = {
  name: '', description: '', repository: '', branch: 'main', privateRepo: false, token: '', rootDirectory: '',
  projectType: '', installCommand: '', buildCommand: '', testCommand: '', outputDirectory: '', startCommand: '', port: null,
  domain: '', autoDeploy: true, autoUpdate: false, healthCheckType: 'HTTP', healthCheckPath: '', cpuLimit: 1, memoryLimitMb: 512, env: '', envSecret: true,
};

/** Parses KEY=VALUE lines. */
function parseEnv(text: string): { name: string; value: string }[] {
  return text.split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#') && l.includes('=')).map((l) => {
    const i = l.indexOf('=');
    return { name: l.slice(0, i).trim(), value: l.slice(i + 1).trim().replace(/^"(.*)"$/, '$1') };
  });
}

/** Add Application wizard (spec section 15): information → repository → detection → deployment → review → deploy. */
export function AddApplicationPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [d, setD] = useState<Draft>(initial);
  const [step, setStep] = useState(0);
  const [branches, setBranches] = useState<string[]>([]);
  const [preview, setPreview] = useState<DetectionPreview | null>(null);
  const [detecting, setDetecting] = useState(false);
  const [detectError, setDetectError] = useState<string | null>(null);
  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => setD((cur) => ({ ...cur, [k]: v }));
  const token = d.privateRepo && d.token ? d.token : undefined;

  // Run detection whenever the user arrives at the detection step with a new repository/branch.
  const lastDetectKey = useRef('');
  const detectKey = `${d.repository}|${d.branch}|${d.rootDirectory}|${token ?? ''}`;
  useEffect(() => {
    if (step !== 2) return;
    if (detectKey === lastDetectKey.current) return;
    lastDetectKey.current = detectKey;
    setDetecting(true);
    setDetectError(null);
    api.detect(d.repository, d.branch, token, d.rootDirectory || undefined)
      .then((p) => {
        setPreview(p);
        if (!d.name.trim()) set('name', p.repository.split('/').pop() ?? '');
      })
      .catch((e) => {
        lastDetectKey.current = '';
        setDetectError(errorMessage(e));
      })
      .finally(() => setDetecting(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step, detectKey]);

  const det = preview?.detection;
  const effectiveType = d.projectType || det?.projectType || null;
  const value = (override: string, detected: string | null | undefined) => (override.trim() ? override.trim() : detected ?? '–');

  const service = (): ServiceSettings => ({
    projectType: d.projectType || null,
    rootDirectory: d.rootDirectory || '.',
    installCommand: d.installCommand.trim() || null,
    buildCommand: d.buildCommand.trim() || null,
    testCommand: d.testCommand.trim() || null,
    outputDirectory: d.outputDirectory.trim() || null,
    startCommand: d.startCommand.trim() || null,
    internalPort: d.port,
    healthCheckType: d.healthCheckType,
    healthCheckPath: d.healthCheckPath.trim() || det?.healthCheckPath || '/',
    cpuLimit: d.cpuLimit,
    memoryLimitMb: d.memoryLimitMb,
  });

  const finish = async () => {
    try {
      const created = await api.applications.create({
        name: d.name.trim(),
        description: d.description.trim() || undefined,
        repository: d.repository.trim(),
        branch: d.branch,
        accessToken: token,
        autoDeploy: d.autoDeploy,
        autoUpdate: d.autoUpdate,
        domains: d.domain.trim() ? [d.domain.trim()] : [],
        service: service(),
        environment: parseEnv(d.env).map((e) => ({ ...e, secret: d.envSecret })),
        deployNow: true,
      });
      toast.show({ title: `${created.summary.name} created`, description: 'The first deployment has been queued.', tone: 'success' });
      navigate(`/applications/${created.summary.id}${d.autoDeploy ? '/webhook' : ''}`);
    } catch (e) {
      toast.show({ title: 'Could not create application', description: errorMessage(e), tone: 'error' });
      throw e;
    }
  };

  return (
    <Page
      title="Add application"
      subtitle="Give Shipyard a Git repository and it will host it"
      breadcrumbs={[{ label: 'Applications', href: appHref('/applications'), onClick: (e) => { e.preventDefault(); navigate('/applications'); } }, { label: 'Add application' }]}
      width="contained"
    >
      <Wizard
        aria-label="Add application"
        step={step}
        onStepChange={setStep}
        onCancel={() => navigate('/applications')}
        onFinish={finish}
        finishLabel="Deploy"
        steps={[
          {
            id: 'info',
            title: 'Application',
            description: 'What are you hosting?',
            content: (
              <FormGroup columns={1}>
                <Input label="Name" required value={d.name} onChange={(e) => set('name', e.target.value)} placeholder="My application"
                  description="Leave empty to use the repository name." />
                <TextArea label="Description" value={d.description} onChange={(e) => set('description', e.target.value)} rows={2} />
              </FormGroup>
            ),
          },
          {
            id: 'repository',
            title: 'Repository',
            description: 'Where does the code live?',
            content: (
              <FormGroup columns={2}>
                <Select label="Provider" value="GITHUB" options={[{ value: 'GITHUB', label: 'GitHub' }]} onChange={() => undefined} />
                <Input label="Repository" required value={d.repository} onChange={(e) => { set('repository', e.target.value); setBranches([]); }}
                  placeholder="github.com/owner/repository" description="owner/repository, github.com/owner/repository or an https clone URL" />
                <Switch label="Private repository" checked={d.privateRepo} onChange={(v) => set('privateRepo', v)}
                  description="Uses a GitHub personal access token with read access to contents." />
                {d.privateRepo && (
                  <PasswordInput label="Access token" value={d.token} onChange={(e) => set('token', e.target.value)} autoComplete="off"
                    description="Stored encrypted; never shown again." />
                )}
                {branches.length > 0 ? (
                  <Select label="Branch" required value={d.branch} options={branches.map((b) => ({ value: b, label: b }))} onChange={(v) => v && set('branch', v)} />
                ) : (
                  <Input label="Branch" required value={d.branch} onChange={(e) => set('branch', e.target.value)} />
                )}
                <Input label="Root directory" value={d.rootDirectory} onChange={(e) => set('rootDirectory', e.target.value)} placeholder="."
                  description="For monorepos: the folder that contains the application." />
              </FormGroup>
            ),
            validate: async () => {
              if (!d.repository.trim()) return 'Enter a repository.';
              try {
                const list = await api.branches(d.repository.trim(), token);
                setBranches(list);
                if (list.length === 0) return 'The repository has no branches.';
                if (!list.includes(d.branch)) {
                  const fallback = list.includes('main') ? 'main' : list.includes('master') ? 'master' : list[0];
                  set('branch', fallback);
                  return `Branch “${d.branch}” does not exist. Selected “${fallback}” — continue again to use it.`;
                }
                return true;
              } catch (e) {
                return `Could not reach the repository: ${errorMessage(e)}`;
              }
            },
          },
          {
            id: 'detection',
            title: 'Detection',
            description: 'What Shipyard found',
            content: detecting ? (
              <BusyIndicator active text="Cloning and inspecting the repository…" />
            ) : detectError ? (
              <MessageStrip tone="error" title="Detection failed">{detectError}</MessageStrip>
            ) : det ? (
              <div className="sy-stack">
                <MessageStrip tone={det.buildStrategy ? 'success' : 'warning'} title={det.buildStrategy ? `Detected ${det.framework ?? humanize(det.buildStrategy)}` : 'Nothing detected'}>
                  {preview && `${preview.repository} @ ${preview.branch} (${shortSha(preview.commitSha)}) · source: ${humanize(det.source)}`}
                  {det.notes.length > 0 && <><br />{det.notes.join(' ')}</>}
                </MessageStrip>
                <DescriptionList
                  columns={3}
                  bordered
                  items={[
                    { term: 'Framework', description: det.framework ?? '–' },
                    { term: 'Type', description: det.projectType ? humanize(det.projectType) : '–' },
                    { term: 'Build strategy', description: humanize(det.buildStrategy) },
                    { term: 'Package manager', description: det.packageManager ?? '–' },
                    { term: 'Install command', description: <code className="sy-mono">{det.installCommand ?? '–'}</code> },
                    { term: 'Build command', description: <code className="sy-mono">{det.buildCommand ?? '–'}</code> },
                    { term: 'Output directory', description: det.outputDirectory ?? '–' },
                    { term: 'Runtime', description: det.runtime ?? '–' },
                    { term: 'Port', description: det.projectType === 'SERVER' ? String(det.port ?? '–') : 'Served by Caddy' },
                  ]}
                />
                <FormGroup title="Overrides" description="Leave empty to use what was detected." columns={2}>
                  <Select label="Application type" value={d.projectType || null} placeholder="Detected"
                    options={[{ value: 'STATIC', label: 'Static' }, { value: 'SERVER', label: 'Server' }]}
                    onChange={(v) => set('projectType', (v ?? '') as Draft['projectType'])} />
                  <Input label="Install command" value={d.installCommand} placeholder={det.installCommand ?? ''} onChange={(e) => set('installCommand', e.target.value)} />
                  <Input label="Build command" value={d.buildCommand} placeholder={det.buildCommand ?? ''} onChange={(e) => set('buildCommand', e.target.value)} />
                  <Input label="Test command" value={d.testCommand} placeholder={det.testCommand ?? 'none'} onChange={(e) => set('testCommand', e.target.value)}
                    description="Runs inside the build container; a failure stops the deployment." />
                  {effectiveType !== 'SERVER' && (
                    <Input label="Output directory" value={d.outputDirectory} placeholder={det.outputDirectory ?? 'dist'} onChange={(e) => set('outputDirectory', e.target.value)} />
                  )}
                  {effectiveType === 'SERVER' && (
                    <>
                      <Input label="Start command" value={d.startCommand} placeholder={det.startCommand ?? ''} onChange={(e) => set('startCommand', e.target.value)} />
                      <NumberInput label="Port" min={1} max={65535} value={d.port} placeholder={String(det.port ?? 8080)} onChange={(v) => set('port', v)} />
                    </>
                  )}
                </FormGroup>
              </div>
            ) : null,
            validate: () => (detecting ? 'Detection is still running.' : detectError ? 'Fix the repository settings to continue.' : true),
          },
          {
            id: 'deployment',
            title: 'Deployment',
            description: 'Domains, automation and health',
            content: (
              <div className="sy-stack">
                <FormGroup columns={2} title="Routing">
                  <Input label="Domain" value={d.domain} onChange={(e) => set('domain', e.target.value)} placeholder="myapp.example.com"
                    description="Point DNS at this server; Caddy obtains the TLS certificate automatically. More domains can be added later." />
                </FormGroup>
                <FormGroup columns={2} title="Automation">
                  <Switch label="Automatic deployment" checked={d.autoDeploy} onChange={(v) => set('autoDeploy', v)}
                    description={`Deploy every push to ${d.branch} through a GitHub webhook.`} />
                  <Switch label="Automatic updates" checked={d.autoUpdate} onChange={(v) => set('autoUpdate', v)}
                    description={`Check ${d.branch} every 90 seconds and deploy new commits. No webhook needed.`} />
                </FormGroup>
                {effectiveType === 'SERVER' && (
                  <FormGroup columns={2} title="Health check and resources">
                    <Select label="Health check" value={d.healthCheckType} onChange={(v) => v && set('healthCheckType', v)}
                      options={[{ value: 'HTTP', label: 'HTTP' }, { value: 'TCP', label: 'TCP port' }, { value: 'PROCESS', label: 'Process running' }, { value: 'NONE', label: 'None' }]} />
                    {d.healthCheckType === 'HTTP' && (
                      <Input label="Health check path" value={d.healthCheckPath} placeholder={det?.healthCheckPath ?? '/'} onChange={(e) => set('healthCheckPath', e.target.value)} />
                    )}
                    <NumberInput label="CPU limit" unit="cores" min={0.1} max={64} step={0.5} precision={1} value={d.cpuLimit} onChange={(v) => set('cpuLimit', v)} />
                    <NumberInput label="Memory limit" unit="MB" min={64} max={262144} step={128} value={d.memoryLimitMb} onChange={(v) => set('memoryLimitMb', v)} />
                  </FormGroup>
                )}
                <FormGroup columns={1} title="Environment variables">
                  <TextArea label="Variables" value={d.env} onChange={(e) => set('env', e.target.value)} rows={4} placeholder={'DATABASE_URL=postgres://…\nNODE_ENV=production'}
                    description="One KEY=VALUE per line. You can manage them individually later." className="sy-mono" />
                  <Checkbox label="Treat these values as secrets" checked={d.envSecret} onChange={(v) => set('envSecret', v)}
                    description="Secrets are masked everywhere and only passed to the running container, not to builds." />
                </FormGroup>
              </div>
            ),
            validate: () => {
              if (d.domain.trim() && !/^[a-z0-9.*-]+\.[a-z0-9-]+$/i.test(d.domain.trim().replace(/^https?:\/\//, ''))) return 'Enter a valid hostname, e.g. app.example.com.';
              const bad = parseEnv(d.env).find((e) => !/^[A-Za-z_][A-Za-z0-9_]*$/.test(e.name));
              if (bad) return `Invalid variable name: ${bad.name}`;
              return true;
            },
          },
          {
            id: 'review',
            title: 'Review',
            description: 'Check before deploying',
            content: (
              <div className="sy-stack">
                <DescriptionList
                  columns={2}
                  bordered
                  items={[
                    { term: 'Application', description: d.name || preview?.repository.split('/').pop() || '–' },
                    { term: 'Repository', description: `${preview?.repository ?? d.repository} · ${d.branch}${d.privateRepo ? ' (private)' : ''}` },
                    { term: 'Build', description: `${det?.framework ?? '–'} · ${value(d.buildCommand, det?.buildCommand)}` },
                    { term: 'Runtime', description: effectiveType === 'STATIC' ? `Static files (${value(d.outputDirectory, det?.outputDirectory)}) served by Caddy`
                      : `${value(d.startCommand, det?.startCommand)} on port ${d.port ?? det?.port ?? 8080} · ${d.cpuLimit} CPU · ${d.memoryLimitMb} MB` },
                    { term: 'Domain', description: d.domain || 'None yet' },
                    { term: 'Webhook', description: d.autoDeploy ? `Automatic deployment on push to ${d.branch}` : 'Manual deployments only' },
                    { term: 'Automatic updates', description: d.autoUpdate ? `Checks ${d.branch} every 90 seconds` : 'Off' },
                    { term: 'Environment', description: `${parseEnv(d.env).length} variable(s)${parseEnv(d.env).length && d.envSecret ? ', secret' : ''}` },
                    { term: 'Health check', description: effectiveType === 'STATIC' ? 'Artifact check' : `${d.healthCheckType}${d.healthCheckType === 'HTTP' ? ` ${d.healthCheckPath || det?.healthCheckPath || '/'}` : ''}` },
                  ]}
                />
                <Text as="p" tone="secondary" size="sm">
                  Selecting Deploy creates the application and queues deployment #1 for the latest commit on {d.branch}. The current deployment is only replaced after a new one passes its health check.
                </Text>
              </div>
            ),
          },
        ]}
      />
    </Page>
  );
}

