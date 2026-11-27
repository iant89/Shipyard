import { ObjectIdentifier } from '@enterprise-ui/react';
import { shortSha } from '../services/format';

/** Commit SHA with message, linking to GitHub when the repository is hosted there. */
export function CommitRef({ sha, message, repositoryUrl }: { sha: string | null; message?: string | null; repositoryUrl?: string | null }) {
  const href = sha && repositoryUrl?.startsWith('https://github.com/')
    ? `${repositoryUrl.replace(/\.git$/, '')}/commit/${sha}`
    : undefined;
  return <ObjectIdentifier title={<code className="sy-mono">{shortSha(sha)}</code>} text={message ?? undefined} href={href} />;
}
