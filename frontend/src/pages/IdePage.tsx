import { useCallback, useEffect, useMemo, useRef, useState, type MouseEvent as ReactMouseEvent, type ReactNode } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import Editor, { type OnMount } from '@monaco-editor/react';
import type * as MonacoTypes from 'monaco-editor';
import { Terminal, type TerminalCommandResult } from '@enterprise-ui/react';
import { configureMonacoTypeServices } from '../monaco';
import { api, errorMessage } from '../services/api';
import type {
  ApplicationDetail, CommitInfo, WorkspaceBuildResult, WorkspaceChangedFile, WorkspaceCommitDiff, WorkspaceDirectory,
  WorkspaceEntry, WorkspaceInfo, WorkspaceSearchMatch,
} from '../services/types';
import { useSession } from '../state/session';
import '../styles/ide.css';

type SidebarView = 'explorer' | 'search' | 'git' | 'history';
type BottomView = 'terminal' | 'problems' | 'output' | 'debug';
type Pane = 'left' | 'right';
type DialogKind = 'new-file' | 'new-folder' | 'rename' | 'copy' | 'move' | 'delete' | 'commit' | 'deploy';

type EditorTab = { path: string; content: string; savedContent: string };
type IdeDialog = { kind: DialogKind; path?: string };
type ContextMenuState = { x: number; y: number; entry: WorkspaceEntry };

type EditorPaneProps = {
  tab: EditorTab;
  pane: Pane;
  theme: string;
  tabSize: number;
  onChange: (path: string, value: string) => void;
  onMount: (editor: MonacoTypes.editor.IStandaloneCodeEditor, monaco: Parameters<OnMount>[1], pane: Pane, path: string) => void;
  onUnmount: (pane: Pane, path: string) => void;
  onValidate: (path: string, markers: MonacoTypes.editor.IMarker[]) => void;
};

function EditorPane({ tab, pane, theme, tabSize, onChange, onMount, onUnmount, onValidate }: EditorPaneProps) {
  const path = tab.path;
  const editorPath = `file:///workspace/${path.split('/').map((part) => encodeURIComponent(part)).join('/')}`;
  const handleMount: OnMount = (editor, monaco) => onMount(editor, monaco, pane, path);
  useEffect(() => () => onUnmount(pane, path), [onUnmount, pane, path]);
  return (
    <Editor
      height="100%"
      path={editorPath}
      language={languageFor(path)}
      theme={theme}
      value={tab.content}
      onChange={(value) => onChange(path, value ?? '')}
      onMount={handleMount}
      onValidate={(markers) => onValidate(path, markers)}
      options={{
        automaticLayout: true,
        minimap: { enabled: true, side: 'right' },
        lineNumbers: 'on',
        glyphMargin: true,
        folding: true,
        foldingHighlight: true,
        showFoldingControls: 'mouseover',
        scrollBeyondLastLine: false,
        smoothScrolling: true,
        fontSize: 13,
        tabSize,
        insertSpaces: true,
        renderWhitespace: 'selection',
        bracketPairColorization: { enabled: true },
        guides: { indentation: true, bracketPairs: true },
        stickyScroll: { enabled: true },
        padding: { top: 12, bottom: 12 },
        wordWrap: 'off',
        suggestOnTriggerCharacters: true,
        quickSuggestions: { other: true, comments: false, strings: true },
        formatOnPaste: true,
        formatOnType: true,
        cursorSmoothCaretAnimation: 'on',
        accessibilitySupport: 'auto',
      }}
    />
  );
}

function languageFor(path: string): string {
  const name = path.toLowerCase();
  if (name.endsWith('.tsx') || name.endsWith('.ts')) return 'typescript';
  if (name.endsWith('.jsx') || name.endsWith('.js') || name.endsWith('.mjs') || name.endsWith('.cjs')) return 'javascript';
  if (name.endsWith('.json') || name.endsWith('.jsonc')) return 'json';
  if (name.endsWith('.html') || name.endsWith('.htm') || name.endsWith('.svg')) return 'html';
  if (name.endsWith('.css')) return 'css';
  if (name.endsWith('.scss')) return 'scss';
  if (name.endsWith('.less')) return 'less';
  if (name.endsWith('.md') || name.endsWith('.mdx')) return 'markdown';
  if (name.endsWith('.yml') || name.endsWith('.yaml')) return 'yaml';
  if (name.endsWith('.xml')) return 'xml';
  if (name.endsWith('.sql')) return 'sql';
  if (name.endsWith('.sh') || name.endsWith('.bash')) return 'shell';
  if (name.endsWith('.py')) return 'python';
  if (name.endsWith('.java')) return 'java';
  if (name.endsWith('.go')) return 'go';
  if (name.endsWith('.rs')) return 'rust';
  if (name.endsWith('.dockerfile') || name.toLowerCase() === 'dockerfile') return 'dockerfile';
  return 'plaintext';
}

function fileGlyph(name: string): string {
  const leaf = name.toLowerCase();
  if (leaf === 'package.json') return 'JS';
  if (leaf.endsWith('.tsx') || leaf.endsWith('.ts')) return 'TS';
  if (leaf.endsWith('.jsx') || leaf.endsWith('.js')) return 'JS';
  if (leaf.endsWith('.json')) return '{}';
  if (leaf.endsWith('.md')) return 'M';
  if (leaf.endsWith('.css') || leaf.endsWith('.scss')) return '#';
  if (leaf.endsWith('.html')) return '<>';
  if (leaf.endsWith('.yml') || leaf.endsWith('.yaml')) return 'Y';
  if (leaf.endsWith('.py')) return 'Py';
  if (leaf.endsWith('.java')) return 'J';
  if (leaf.endsWith('.go')) return 'Go';
  if (leaf === 'dockerfile') return 'D';
  return '·';
}

function parentPath(path: string): string {
  const index = path.lastIndexOf('/');
  return index < 0 ? '' : path.slice(0, index);
}

function leafName(path: string): string {
  return path.slice(path.lastIndexOf('/') + 1);
}

function remapPath(path: string, from: string, to: string): string {
  return path === from || path.startsWith(`${from}/`) ? `${to}${path.slice(from.length)}` : path;
}

function joinPath(directory: string, name: string): string {
  return directory ? `${directory}/${name}` : name;
}

function ancestorDirectories(path: string, includeLeaf = false): string[] {
  const parts = path.split('/').filter(Boolean);
  if (!includeLeaf) parts.pop();
  const paths = [''];
  let current = '';
  for (const part of parts) {
    current = joinPath(current, part);
    paths.push(current);
  }
  return paths;
}

function isDirty(tab: EditorTab): boolean {
  return tab.content !== tab.savedContent;
}

function formatSize(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function formatCommitDate(timestamp: string): string {
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return timestamp;
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }).format(date);
}

function diffLineClass(line: string): string {
  if (line.startsWith('diff --git') || line.startsWith('index ') || line.startsWith('---') || line.startsWith('+++') || line.startsWith('@@')) return 'meta';
  if (line.startsWith('+')) return 'added';
  if (line.startsWith('-')) return 'removed';
  return 'context';
}

function markerSeverity(severity: number): string {
  if (severity >= 8) return 'error';
  if (severity >= 4) return 'warning';
  return 'info';
}

export function IdePage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const { status: session } = useSession();
  const [detail, setDetail] = useState<ApplicationDetail | null>(null);
  const [workspace, setWorkspace] = useState<WorkspaceInfo | null>(null);
  const [historyCommits, setHistoryCommits] = useState<CommitInfo[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyCommit, setHistoryCommit] = useState<CommitInfo | null>(null);
  const [historyFiles, setHistoryFiles] = useState<WorkspaceChangedFile[]>([]);
  const [historyFilesLoading, setHistoryFilesLoading] = useState(false);
  const [historyFilesTruncated, setHistoryFilesTruncated] = useState(false);
  const [selectedHistoryPath, setSelectedHistoryPath] = useState<string | null>(null);
  const [historyDiff, setHistoryDiff] = useState<WorkspaceCommitDiff | null>(null);
  const [historyDiffLoading, setHistoryDiffLoading] = useState(false);
  const [directories, setDirectories] = useState<Record<string, WorkspaceDirectory>>({});
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(['']));
  const [selectedDirectory, setSelectedDirectory] = useState('');
  const [sidebar, setSidebar] = useState<SidebarView>('explorer');
  const [tabs, setTabs] = useState<EditorTab[]>([]);
  const [activePath, setActivePath] = useState<string | null>(null);
  const [secondaryPath, setSecondaryPath] = useState<string | null>(null);
  const [split, setSplit] = useState(false);
  const [bottomOpen, setBottomOpen] = useState(true);
  const [bottomView, setBottomView] = useState<BottomView>('terminal');
  const [bottomHeight, setBottomHeight] = useState(250);
  const [theme, setTheme] = useState(() => localStorage.getItem('shipyard-ide-theme') ?? 'vs-dark');
  const [tabSize, setTabSize] = useState(() => Number(localStorage.getItem('shipyard-ide-tab-size') ?? '2'));
  const [formatOnSave, setFormatOnSave] = useState(() => localStorage.getItem('shipyard-ide-format-on-save') !== 'false');
  const [query, setQuery] = useState('');
  const [searchMatches, setSearchMatches] = useState<WorkspaceSearchMatch[]>([]);
  const [searchBusy, setSearchBusy] = useState(false);
  const [quickOpen, setQuickOpen] = useState(false);
  const [quickQuery, setQuickQuery] = useState('');
  const [quickFiles, setQuickFiles] = useState<WorkspaceEntry[]>([]);
  const [quickFilesLoading, setQuickFilesLoading] = useState(false);
  const [quickFilesTruncated, setQuickFilesTruncated] = useState(false);
  const [dialog, setDialog] = useState<IdeDialog | null>(null);
  const [dialogValue, setDialogValue] = useState('');
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<'save' | 'build' | 'deploy' | 'push' | 'branch' | 'fetch' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState('Ready');
  const [output, setOutput] = useState<string[]>([]);
  const [problemsByPath, setProblemsByPath] = useState<Record<string, MonacoTypes.editor.IMarker[]>>({});
  const [breakpoints, setBreakpoints] = useState<Record<string, number[]>>({});
  const [buildResult, setBuildResult] = useState<WorkspaceBuildResult | null>(null);
  const [terminalCwd, setTerminalCwd] = useState<string | null>(null);
  const [terminalHistory, setTerminalHistory] = useState<string[]>([]);
  const historyRequestRef = useRef(0);
  const editorRefs = useRef<Record<Pane, MonacoTypes.editor.IStandaloneCodeEditor | null>>({ left: null, right: null });
  const editorPaths = useRef<Record<Pane, string | null>>({ left: null, right: null });
  const decorationIds = useRef<Record<Pane, string[]>>({ left: [], right: [] });
  const breakpointsRef = useRef(breakpoints);
  const quickInputRef = useRef<HTMLInputElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const uploadInputRef = useRef<HTMLInputElement>(null);
  const saveRef = useRef<() => void>(() => undefined);

  const dirtyTabs = useMemo(() => tabs.filter(isDirty), [tabs]);
  const currentDeployment = detail?.summary.currentDeployment ?? null;
  useEffect(() => { setTerminalCwd(null); }, [currentDeployment?.id]);
  const navigateSafely = useCallback((to: string) => {
    if (dirtyTabs.length && !window.confirm(`Discard unsaved changes in ${dirtyTabs.length} open file${dirtyTabs.length === 1 ? '' : 's'} and leave the IDE?`)) return;
    navigate(to);
  }, [dirtyTabs.length, navigate]);
  const terminalEnabled = currentDeployment?.projectType === 'SERVER' && detail?.summary.status === 'RUNNING';
  const allProblems = useMemo(() => Object.entries(problemsByPath).flatMap(([path, markers]) => markers.map((marker) => ({ path, marker }))), [problemsByPath]);
  const allBreakpoints = useMemo(() => Object.entries(breakpoints).flatMap(([path, lines]) => lines.map((line) => ({ path, line }))), [breakpoints]);

  const appendOutput = useCallback((lines: string[]) => {
    if (!lines.length) return;
    setOutput((current) => [...current, ...lines].slice(-1_000));
  }, []);

  const refreshInfo = useCallback(async () => {
    const info = await api.workspace.info(id);
    setWorkspace(info);
    return info;
  }, [id]);

  const refreshHistory = useCallback(async () => {
    const requestId = ++historyRequestRef.current;
    setHistoryLoading(true);
    setError(null);
    try {
      const commits = await api.workspace.history(id, 50);
      if (requestId !== historyRequestRef.current) return;
      setHistoryCommits(commits);
      setHistoryCommit((current) => commits.find((commit) => commit.sha === current?.sha) ?? commits[0] ?? null);
    } catch (err) {
      if (requestId === historyRequestRef.current) setError(errorMessage(err));
    } finally {
      if (requestId === historyRequestRef.current) setHistoryLoading(false);
    }
  }, [id]);

  const loadDirectory = useCallback(async (path: string) => {
    const listing = await api.workspace.files(id, path);
    setDirectories((current) => ({ ...current, [path]: listing }));
    return listing;
  }, [id]);

  const loadInitial = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [application, info] = await Promise.all([api.applications.get(id), api.workspace.info(id)]);
      setDetail(application);
      setWorkspace(info);
      const root = await api.workspace.files(id, '');
      setDirectories({ '': root });
      setExpanded(new Set(['']));
      setNotice('Workspace ready');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void loadInitial(); }, [loadInitial]);

  useEffect(() => {
    if (sidebar === 'history') void refreshHistory();
  }, [refreshHistory, sidebar, workspace?.branch, workspace?.headSha]);

  useEffect(() => {
    if (sidebar !== 'history' || !historyCommit) {
      setHistoryFilesLoading(false);
      return;
    }
    let cancelled = false;
    setHistoryFilesLoading(true);
    setHistoryFiles([]);
    setHistoryFilesTruncated(false);
    setSelectedHistoryPath(null);
    setHistoryDiff(null);
    setError(null);
    api.workspace.commitFiles(id, historyCommit.sha).then((result) => {
      if (cancelled) return;
      setHistoryFiles(result.files);
      setHistoryFilesTruncated(result.truncated);
      setSelectedHistoryPath(result.files[0]?.path ?? null);
    }).catch((err) => {
      if (!cancelled) setError(errorMessage(err));
    }).finally(() => {
      if (!cancelled) setHistoryFilesLoading(false);
    });
    return () => { cancelled = true; };
  }, [historyCommit?.sha, id, sidebar]);

  useEffect(() => {
    if (sidebar !== 'history' || !historyCommit || !selectedHistoryPath) {
      setHistoryDiff(null);
      setHistoryDiffLoading(false);
      return;
    }
    let cancelled = false;
    setHistoryDiffLoading(true);
    setHistoryDiff(null);
    api.workspace.commitDiff(id, historyCommit.sha, selectedHistoryPath).then((result) => {
      if (!cancelled) setHistoryDiff(result);
    }).catch((err) => {
      if (!cancelled) setError(errorMessage(err));
    }).finally(() => {
      if (!cancelled) setHistoryDiffLoading(false);
    });
    return () => { cancelled = true; };
  }, [historyCommit?.sha, id, selectedHistoryPath, sidebar]);

  useEffect(() => {
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (dirtyTabs.length) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [dirtyTabs.length]);

  useEffect(() => {
    localStorage.setItem('shipyard-ide-theme', theme);
  }, [theme]);
  useEffect(() => {
    localStorage.setItem('shipyard-ide-tab-size', String(tabSize));
  }, [tabSize]);
  useEffect(() => {
    localStorage.setItem('shipyard-ide-format-on-save', String(formatOnSave));
  }, [formatOnSave]);

  const setBreakpointDecorations = useCallback((pane: Pane, path: string, lines: number[]) => {
    const editor = editorRefs.current[pane];
    if (!editor || editorPaths.current[pane] !== path) return;
    const next = editor.deltaDecorations(decorationIds.current[pane], lines.map((line) => ({
      range: { startLineNumber: line, startColumn: 1, endLineNumber: line, endColumn: 1 },
      options: {
        isWholeLine: false,
        glyphMarginClassName: 'sy-ide-breakpoint-glyph',
        glyphMarginHoverMessage: { value: 'Breakpoint' },
      },
    })));
    decorationIds.current[pane] = next;
  }, []);

  const toggleBreakpoint = useCallback((path: string, line: number) => {
    const current = breakpointsRef.current[path] ?? [];
    const nextLines = current.includes(line) ? current.filter((n) => n !== line) : [...current, line].sort((a, b) => a - b);
    const next = { ...breakpointsRef.current, [path]: nextLines };
    if (!nextLines.length) delete next[path];
    breakpointsRef.current = next;
    setBreakpoints(next);
    (['left', 'right'] as Pane[]).forEach((pane) => setBreakpointDecorations(pane, path, nextLines));
  }, [setBreakpointDecorations]);

  const onEditorMount: EditorPaneProps['onMount'] = useCallback((editor, monaco, pane, path) => {
    configureMonacoTypeServices(monaco);
    editorRefs.current[pane] = editor;
    editorPaths.current[pane] = path;
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => saveRef.current());
    editor.onMouseDown((event) => {
      if (event.target.type === monaco.editor.MouseTargetType.GUTTER_GLYPH_MARGIN && event.target.position) {
        toggleBreakpoint(path, event.target.position.lineNumber);
      }
    });
    if (path.endsWith('.ts') || path.endsWith('.tsx') || path.endsWith('.js') || path.endsWith('.jsx')) {
      monaco.languages.typescript.typescriptDefaults.setCompilerOptions({
        allowNonTsExtensions: true,
        jsx: monaco.languages.typescript.JsxEmit.ReactJSX,
        target: monaco.languages.typescript.ScriptTarget.ES2020,
        moduleResolution: monaco.languages.typescript.ModuleResolutionKind.NodeJs,
        allowJs: true,
        checkJs: false,
      });
      monaco.languages.typescript.javascriptDefaults.setCompilerOptions({
        allowNonTsExtensions: true,
        jsx: monaco.languages.typescript.JsxEmit.ReactJSX,
        target: monaco.languages.typescript.ScriptTarget.ES2020,
        allowJs: true,
        checkJs: false,
      });
    }
    setBreakpointDecorations(pane, path, breakpointsRef.current[path] ?? []);
  }, [setBreakpointDecorations, toggleBreakpoint]);

  const onEditorUnmount = useCallback((pane: Pane, path: string) => {
    if (editorPaths.current[pane] === path) {
      editorRefs.current[pane] = null;
      editorPaths.current[pane] = null;
      decorationIds.current[pane] = [];
    }
  }, []);

  const updateContent = useCallback((path: string, content: string) => {
    setTabs((current) => current.map((tab) => tab.path === path ? { ...tab, content } : tab));
  }, []);

  const handleValidate = useCallback((path: string, markers: MonacoTypes.editor.IMarker[]) => {
    setProblemsByPath((current) => ({ ...current, [path]: markers }));
  }, []);

  const openFile = useCallback(async (path: string, toSide = false, line?: number) => {
    setError(null);
    try {
      let tab = tabs.find((item) => item.path === path);
      if (!tab) {
        const file = await api.workspace.read(id, path);
        tab = { path, content: file.content, savedContent: file.content };
        setTabs((current) => current.some((item) => item.path === path) ? current : [...current, tab!]);
      }
      if (toSide) {
        setSplit(true);
        setSecondaryPath(path);
      } else {
        setActivePath(path);
      }
      const ancestors = ancestorDirectories(path);
      setExpanded((current) => new Set([...current, ...ancestors]));
      setSelectedDirectory(parentPath(path));
      const missingDirectories = ancestors.filter((directory) => !directories[directory]);
      if (missingDirectories.length) {
        const results = await Promise.all(missingDirectories.map(async (directory) => [directory, await api.workspace.files(id, directory)] as const));
        setDirectories((current) => ({ ...current, ...Object.fromEntries(results) }));
      }
      if (line) window.setTimeout(() => {
        const editor = toSide ? editorRefs.current.right : editorRefs.current.left;
        editor?.revealLineInCenter(line);
        editor?.setPosition({ lineNumber: line, column: 1 });
        editor?.focus();
      }, 80);
    } catch (err) {
      setError(errorMessage(err));
    }
  }, [directories, id, tabs]);

  const closeTab = useCallback((path: string) => {
    const tab = tabs.find((item) => item.path === path);
    if (tab && isDirty(tab) && !window.confirm(`Discard unsaved changes to ${leafName(path)}?`)) return;
    const remaining = tabs.filter((item) => item.path !== path);
    setTabs(remaining);
    setProblemsByPath((current) => { const next = { ...current }; delete next[path]; return next; });
    setBreakpoints((current) => { const next = { ...current }; delete next[path]; breakpointsRef.current = next; return next; });
    if (activePath === path) setActivePath(remaining.at(-1)?.path ?? null);
    if (secondaryPath === path) setSecondaryPath(null);
  }, [activePath, secondaryPath, tabs]);

  const saveFile = useCallback(async (path: string): Promise<boolean> => {
    const tab = tabs.find((item) => item.path === path);
    if (!tab) return true;
    const editor = (['left', 'right'] as Pane[]).map((pane) => editorPaths.current[pane] === path ? editorRefs.current[pane] : null).find(Boolean) ?? null;
    if (formatOnSave && editor) {
      try { await editor.getAction('editor.action.formatDocument')?.run(); } catch { /* formatter is optional for some languages */ }
    }
    const content = editor?.getValue() ?? tab.content;
    try {
      await api.workspace.write(id, path, content);
      const latestContent = (['left', 'right'] as Pane[])
        .map((pane) => editorPaths.current[pane] === path ? editorRefs.current[pane]?.getValue() : undefined)
        .find((value): value is string => value !== undefined);
      setTabs((current) => current.map((item) => item.path === path
        ? { ...item, content: latestContent ?? item.content, savedContent: content }
        : item));
      setNotice(`Saved ${leafName(path)}`);
      return true;
    } catch (err) {
      setError(errorMessage(err));
      setNotice(`Could not save ${leafName(path)}`);
      return false;
    }
  }, [formatOnSave, id, tabs]);

  const saveAll = useCallback(async (): Promise<boolean> => {
    if (!dirtyTabs.length) {
      setNotice('All files are saved');
      return true;
    }
    setBusy('save');
    setError(null);
    for (const tab of dirtyTabs) {
      const saved = await saveFile(tab.path);
      if (!saved) {
        setBusy(null);
        return false;
      }
    }
    setBusy(null);
    void refreshInfo().catch(() => undefined);
    return true;
  }, [dirtyTabs, refreshInfo, saveFile]);

  saveRef.current = () => { void saveAll(); };

  const flattenFiles = useMemo(() => {
    const out: WorkspaceEntry[] = [];
    Object.values(directories).forEach((listing) => listing.entries.filter((entry) => entry.kind === 'FILE').forEach((entry) => out.push(entry)));
    return [...new Map(out.map((entry) => [entry.path, entry])).values()].sort((a, b) => a.path.localeCompare(b.path));
  }, [directories]);

  const runSearch = useCallback(async () => {
    if (query.trim().length < 2) {
      setSearchMatches([]);
      return;
    }
    setSearchBusy(true);
    setError(null);
    try {
      const result = await api.workspace.search(id, query);
      setSearchMatches(result.matches);
      setNotice(`${result.matches.length} search result${result.matches.length === 1 ? '' : 's'}${result.truncated ? ' · results limited' : ''}`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSearchBusy(false);
    }
  }, [id, query]);

  const refreshTree = useCallback(async (paths: string[] = [...expanded]) => {
    const results = await Promise.all(paths.map(async (path) => [path, await api.workspace.files(id, path)] as const));
    setDirectories(Object.fromEntries(results));
    await refreshInfo();
  }, [expanded, id, refreshInfo]);

  const toggleFolder = useCallback(async (path: string) => {
    const isOpen = expanded.has(path);
    const next = new Set(expanded);
    if (isOpen) next.delete(path);
    else next.add(path);
    setExpanded(next);
    setSelectedDirectory(path);
    if (!isOpen && !directories[path]) {
      try { await loadDirectory(path); } catch (err) { setError(errorMessage(err)); }
    }
  }, [directories, expanded, loadDirectory]);

  const handleUpload = useCallback(async (file: File | undefined) => {
    if (!file) return;
    setBusy('save');
    setError(null);
    try {
      await api.workspace.upload(id, selectedDirectory, file);
      await refreshTree();
      setNotice(`Uploaded ${file.name}`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  }, [id, refreshTree, selectedDirectory]);

  const downloadFile = useCallback(async (path: string) => {
    try {
      const blob = await api.workspace.download(id, path);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = leafName(path);
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
    } catch (err) {
      setError(errorMessage(err));
    }
  }, [id]);

  const beginDialog = useCallback((kind: DialogKind, path?: string) => {
    setContextMenu(null);
    setDialog({ kind, path });
    if (kind === 'new-file' || kind === 'new-folder') {
      const directory = path ?? selectedDirectory;
      setDialogValue(directory ? `${directory}/` : '');
    }
    else if (kind === 'rename' && path) setDialogValue(leafName(path));
    else if (kind === 'copy' && path) setDialogValue(`${parentPath(path) ? `${parentPath(path)}/` : ''}${leafName(path)}-copy`);
    else if (kind === 'move' && path) setDialogValue(path);
    else if (kind === 'commit') setDialogValue('Update application from Shipyard IDE');
    else if (kind === 'deploy') setDialogValue('Deploy from Shipyard IDE');
    else setDialogValue('');
  }, [selectedDirectory]);

  const runDialogAction = useCallback(async () => {
    if (!dialog) return;
    setError(null);
    try {
      if (dialog.kind === 'new-file' || dialog.kind === 'new-folder') {
        const path = dialogValue.trim().replace(/^\/+|\/+$/g, '');
        if (!path) throw new Error('Enter a project-relative path.');
        if (dialog.kind === 'new-file') await api.workspace.createFile(id, path);
        else await api.workspace.createDirectory(id, path);
        const nextExpanded = [...new Set([...expanded, ...ancestorDirectories(path, dialog.kind === 'new-folder')])];
        setExpanded(new Set(nextExpanded));
        setSelectedDirectory(dialog.kind === 'new-folder' ? path : parentPath(path));
        setDialog(null);
        await refreshTree(nextExpanded);
        if (dialog.kind === 'new-file') await openFile(path);
        setNotice(dialog.kind === 'new-file' ? `Created ${leafName(path)}` : `Created folder ${leafName(path)}`);
      } else if (dialog.kind === 'rename' && dialog.path) {
        await api.workspace.rename(id, dialog.path, dialogValue.trim());
        const oldPath = dialog.path;
        const newPath = joinPath(parentPath(oldPath), dialogValue.trim());
        setTabs((current) => current.map((tab) => { const next = remapPath(tab.path, oldPath, newPath); return next === tab.path ? tab : { ...tab, path: next }; }));
        setActivePath((current) => current ? remapPath(current, oldPath, newPath) : current);
        setSecondaryPath((current) => current ? remapPath(current, oldPath, newPath) : current);
        setProblemsByPath((current) => Object.fromEntries(Object.entries(current).map(([path, markers]) => [remapPath(path, oldPath, newPath), markers])));
        setBreakpoints((current) => {
          const next = Object.fromEntries(Object.entries(current).map(([path, lines]) => [remapPath(path, oldPath, newPath), lines]));
          breakpointsRef.current = next;
          return next;
        });
        const nextExpanded = [...expanded].map((path) => remapPath(path, oldPath, newPath));
        setExpanded(new Set(nextExpanded));
        setSelectedDirectory((current) => remapPath(current, oldPath, newPath));
        setDirectories({});
        setDialog(null);
        await refreshTree(nextExpanded);
        setNotice(`Renamed ${leafName(oldPath)}`);
      } else if ((dialog.kind === 'copy' || dialog.kind === 'move') && dialog.path) {
        const destination = dialogValue.trim().replace(/^\/+|\/+$/g, '');
        if (!destination) throw new Error('Enter a project-relative destination path.');
        if (dialog.kind === 'copy') await api.workspace.copy(id, dialog.path, destination);
        else await api.workspace.move(id, dialog.path, destination);
        let treePaths = [...expanded];
        if (dialog.kind === 'move') {
          const oldPath = dialog.path;
          setTabs((current) => current.map((tab) => { const next = remapPath(tab.path, oldPath, destination); return next === tab.path ? tab : { ...tab, path: next }; }));
          setActivePath((current) => current ? remapPath(current, oldPath, destination) : current);
          setSecondaryPath((current) => current ? remapPath(current, oldPath, destination) : current);
          setProblemsByPath((current) => Object.fromEntries(Object.entries(current).map(([path, markers]) => [remapPath(path, oldPath, destination), markers])));
          setBreakpoints((current) => {
            const next = Object.fromEntries(Object.entries(current).map(([path, lines]) => [remapPath(path, oldPath, destination), lines]));
            breakpointsRef.current = next;
            return next;
          });
          treePaths = treePaths.map((path) => remapPath(path, oldPath, destination));
        }
        treePaths = [...new Set([...treePaths, ...ancestorDirectories(destination)])];
        setExpanded(new Set(treePaths));
        setSelectedDirectory(parentPath(destination));
        setDirectories({});
        setDialog(null);
        await refreshTree(treePaths);
        setNotice(`${dialog.kind === 'copy' ? 'Copied' : 'Moved'} ${leafName(dialog.path)}`);
      } else if (dialog.kind === 'delete' && dialog.path) {
        await api.workspace.remove(id, dialog.path);
        setTabs((current) => current.filter((tab) => tab.path !== dialog.path && !tab.path.startsWith(`${dialog.path}/`)));
        setProblemsByPath((current) => Object.fromEntries(Object.entries(current).filter(([path]) => path !== dialog.path && !path.startsWith(`${dialog.path}/`))));
        setBreakpoints((current) => {
          const next = Object.fromEntries(Object.entries(current).filter(([path]) => path !== dialog.path && !path.startsWith(`${dialog.path}/`)));
          breakpointsRef.current = next;
          return next;
        });
        if (activePath === dialog.path || activePath?.startsWith(`${dialog.path}/`)) setActivePath(null);
        if (secondaryPath === dialog.path || secondaryPath?.startsWith(`${dialog.path}/`)) setSecondaryPath(null);
        const nextExpanded = [...expanded].filter((path) => path !== dialog.path && !path.startsWith(`${dialog.path}/`));
        setExpanded(new Set(nextExpanded));
        setDirectories({});
        setSelectedDirectory(parentPath(dialog.path));
        setDialog(null);
        await refreshTree(nextExpanded);
        setNotice(`Deleted ${leafName(dialog.path)}`);
      } else if (dialog.kind === 'commit') {
        const saved = await saveAll();
        if (!saved) return;
        const info = await api.workspace.commit(id, dialogValue.trim());
        setWorkspace(info);
        setDialog(null);
        setNotice('Changes committed locally');
        appendOutput([`[${new Date().toLocaleTimeString()}] Commit created on ${info.branch} · ${info.headSha.slice(0, 8)}`]);
      } else if (dialog.kind === 'deploy') {
        const saved = await saveAll();
        if (!saved) return;
        setBusy('deploy');
        const deployment = await api.workspace.deploy(id, dialogValue.trim());
        void refreshInfo().catch(() => undefined);
        void refreshHistory();
        setDialog(null);
        setNotice(`Deployment #${deployment.number} queued`);
        appendOutput([`[${new Date().toLocaleTimeString()}] Deployment #${deployment.number} queued from ${deployment.branch}`]);
        setBottomView('output');
        setBottomOpen(true);
      }
    } catch (err) {
      setError(errorMessage(err));
      setNotice('Action failed');
    } finally {
      setBusy(null);
    }
  }, [activePath, appendOutput, dialog, dialogValue, expanded, id, openFile, refreshHistory, refreshInfo, refreshTree, saveAll, secondaryPath]);

  const runBuild = useCallback(async () => {
    if (!(await saveAll())) return;
    setBusy('build');
    setError(null);
    setBuildResult(null);
    setBottomView('output');
    setBottomOpen(true);
    appendOutput([`[${new Date().toLocaleTimeString()}] Build started from the editable workspace…`]);
    try {
      const result = await api.workspace.build(id);
      setBuildResult(result);
      appendOutput(result.output.map((line) => `[build] ${line}`));
      setNotice(result.success ? `Build passed${result.framework ? ` · ${result.framework}` : ''}` : 'Build failed');
      if (!result.success && result.error) setError(result.error);
    } catch (err) {
      const message = errorMessage(err);
      setError(message);
      appendOutput([`[build] ${message}`]);
    } finally {
      setBusy(null);
    }
  }, [appendOutput, id, saveAll]);

  const pushChanges = useCallback(async () => {
    if (!(await saveAll())) return;
    setBusy('push');
    setError(null);
    try {
      const info = await api.workspace.push(id);
      setWorkspace(info);
      setNotice(`Pushed ${info.branch}`);
      appendOutput([`[${new Date().toLocaleTimeString()}] Pushed ${info.branch} to origin.`]);
    } catch (err) {
      setError(errorMessage(err));
      setNotice('Push failed');
    } finally {
      setBusy(null);
    }
  }, [appendOutput, id, saveAll]);

  const fetchRemote = useCallback(async () => {
    setBusy('fetch');
    setError(null);
    setNotice('Fetching branches from origin…');
    try {
      const info = await api.workspace.fetch(id);
      setWorkspace(info);
      setNotice(`Fetched origin · ${info.branches.length} branch${info.branches.length === 1 ? '' : 'es'}`);
    } catch (err) {
      setError(errorMessage(err));
      setNotice('Fetch failed');
    } finally {
      setBusy(null);
    }
  }, [id]);

  const switchBranch = useCallback(async (branch: string) => {
    if (!branch || branch === workspace?.branch) return;
    if (workspace?.changes.length) {
      setError('Commit or discard saved workspace changes before switching branches.');
      return;
    }
    if (dirtyTabs.length && !window.confirm('Discard unsaved editor changes and switch branches?')) return;
    setBusy('branch');
    setError(null);
    try {
      const info = await api.workspace.checkout(id, branch);
      setWorkspace(info);
      setTabs([]);
      setActivePath(null);
      setSecondaryPath(null);
      setProblemsByPath({});
      setBreakpoints({});
      breakpointsRef.current = {};
      setDirectories({});
      setExpanded(new Set(['']));
      setSelectedDirectory('');
      await loadDirectory('');
      setNotice(`Switched to ${branch}`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  }, [dirtyTabs.length, id, loadDirectory, workspace?.branch, workspace?.changes.length]);

  const openQuickOpen = useCallback(() => {
    setQuickOpen(true);
    setQuickQuery('');
    setQuickFiles([]);
    setQuickFilesTruncated(false);
    setQuickFilesLoading(true);
    api.workspace.allFiles(id).then((index) => {
      setQuickFiles(index.entries);
      setQuickFilesTruncated(index.truncated);
    }).catch((err) => {
      setQuickFiles(flattenFiles);
      setQuickFilesTruncated(false);
      setError(errorMessage(err));
    }).finally(() => setQuickFilesLoading(false));
  }, [flattenFiles, id]);

  const openQuickSearch = useCallback(() => {
    setSidebar('search');
    window.setTimeout(() => searchInputRef.current?.focus(), 30);
  }, []);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      const mod = event.metaKey || event.ctrlKey;
      if (mod && event.key.toLowerCase() === 's') {
        event.preventDefault();
        void saveAll();
      } else if (mod && event.key.toLowerCase() === 'p') {
        event.preventDefault();
        openQuickOpen();
      } else if (mod && event.shiftKey && event.key.toLowerCase() === 'f') {
        event.preventDefault();
        openQuickSearch();
      } else if (mod && event.key === '`') {
        event.preventDefault();
        setBottomView('terminal');
        setBottomOpen(true);
      } else if (event.key === 'Escape') {
        setQuickOpen(false);
        setContextMenu(null);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [openQuickOpen, openQuickSearch, saveAll]);

  useEffect(() => {
    if (quickOpen) window.setTimeout(() => quickInputRef.current?.focus(), 30);
  }, [quickOpen]);

  const filteredQuickFiles = useMemo(() => {
    const q = quickQuery.trim().toLowerCase();
    return quickFiles.filter((entry) => !q || entry.path.toLowerCase().includes(q)).slice(0, 80);
  }, [quickFiles, quickQuery]);

  const showContextMenu = (event: ReactMouseEvent, entry: WorkspaceEntry) => {
    event.preventDefault();
    event.stopPropagation();
    setContextMenu({
      x: Math.min(event.clientX, window.innerWidth - 235),
      y: Math.min(event.clientY, window.innerHeight - 320),
      entry,
    });
  };

  const startPanelResize = (event: ReactMouseEvent<HTMLDivElement>) => {
    event.preventDefault();
    const startY = event.clientY;
    const startHeight = bottomHeight;
    const move = (pointer: MouseEvent) => setBottomHeight(Math.max(140, Math.min(window.innerHeight - 190, startHeight + startY - pointer.clientY)));
    const finish = () => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', finish);
    };
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', finish, { once: true });
  };

  const renderTree = (directory: string, depth: number): ReactNode => {
    const listing = directories[directory];
    if (!listing) return null;
    return listing.entries.map((entry) => {
      const isFolder = entry.kind === 'DIRECTORY';
      const isOpen = expanded.has(entry.path);
      const selected = isFolder ? selectedDirectory === entry.path : activePath === entry.path || secondaryPath === entry.path;
      return (
        <div key={entry.path}>
          <div
            className={`sy-ide-tree-row ${selected ? 'is-selected' : ''} ${entry.kind === 'SYMLINK' ? 'is-disabled' : ''}`}
            style={{ paddingLeft: `${10 + depth * 14}px` }}
            onContextMenu={(event) => showContextMenu(event, entry)}
            onClick={(event) => {
              if (isFolder) void toggleFolder(entry.path);
              else if (entry.kind === 'FILE') void openFile(entry.path, event.shiftKey);
            }}
            title={entry.kind === 'SYMLINK' ? 'Symbolic links are not followed by the workspace API' : entry.kind === 'FILE' ? `${entry.path} · ${formatSize(entry.size)}` : entry.path}
            role="treeitem"
            aria-expanded={isFolder ? isOpen : undefined}
            aria-selected={selected}
          >
            {isFolder ? <span className="sy-ide-tree-chevron">{isOpen ? '⌄' : '›'}</span> : <span className="sy-ide-tree-chevron" />}
            <span className={`sy-ide-file-glyph ${isFolder ? 'folder' : languageFor(entry.name)}`}>{isFolder ? (isOpen ? '▾' : '▸') : fileGlyph(entry.name)}</span>
            <span className="sy-ide-tree-name">{entry.name}</span>
            {entry.kind === 'SYMLINK' && <span className="sy-ide-tree-meta">link</span>}
          </div>
          {isFolder && isOpen && <div role="group">{renderTree(entry.path, depth + 1)}{directories[entry.path]?.truncated && <div className="sy-ide-truncated">Folder limited to the first 2,000 entries.</div>}</div>}
        </div>
      );
    });
  };

  const renderSidebar = () => {
    if (sidebar === 'explorer') {
      return (
        <>
          <div className="sy-ide-sidebar-toolbar">
            <span>Explorer</span>
            <div className="sy-ide-tool-buttons">
              <button title="New file" onClick={() => beginDialog('new-file')}>＋</button>
              <button title="New folder" onClick={() => beginDialog('new-folder')}>▰</button>
              <button title="Upload file" onClick={() => uploadInputRef.current?.click()}>↑</button>
              <button title="Refresh explorer" onClick={() => void refreshTree()}>⟳</button>
              <input ref={uploadInputRef} type="file" hidden onChange={(event) => { void handleUpload(event.currentTarget.files?.[0]); event.currentTarget.value = ''; }} />
            </div>
          </div>
          <div className="sy-ide-project-root" onContextMenu={(event) => {
            event.preventDefault();
            setSelectedDirectory('');
            setContextMenu({ x: Math.min(event.clientX, window.innerWidth - 235), y: Math.min(event.clientY, window.innerHeight - 320), entry: { name: detail?.summary.name ?? 'Project', path: '', kind: 'DIRECTORY', size: 0 } });
          }}>
            <button className="sy-ide-root-toggle" onClick={() => void toggleFolder('')}>{expanded.has('') ? '⌄' : '›'}</button>
            <span className="sy-ide-root-mark">▣</span>
            <span className="sy-ide-root-label">{detail?.summary.name ?? 'PROJECT'}</span>
            <span className="sy-ide-root-branch">{workspace?.branch}</span>
          </div>
          <div className="sy-ide-tree" role="tree" aria-label="Project files">
            {expanded.has('') ? renderTree('', 0) : <button className="sy-ide-empty-action" onClick={() => void toggleFolder('')}>Open project files</button>}
            {directories['']?.truncated && <div className="sy-ide-truncated">Explorer limited to the first 2,000 entries.</div>}
          </div>
        </>
      );
    }

    if (sidebar === 'search') {
      return (
        <>
          <div className="sy-ide-sidebar-toolbar"><span>Search</span><button className="sy-ide-text-button" onClick={() => { setQuery(''); setSearchMatches([]); }}>Clear</button></div>
          <form className="sy-ide-search-form" onSubmit={(event) => { event.preventDefault(); void runSearch(); }}>
            <div className="sy-ide-search-input-wrap"><span>⌕</span><input ref={searchInputRef} value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search in files" aria-label="Search in project" /></div>
            <button className="sy-ide-search-submit" disabled={searchBusy || query.trim().length < 2}>{searchBusy ? '…' : 'Search'}</button>
          </form>
          <div className="sy-ide-search-note">Search checks file names and text, skipping dependencies and build output.</div>
          <div className="sy-ide-search-results">
            {searchMatches.map((match, index) => (
              <button key={`${match.path}:${match.lineNumber}:${index}`} className="sy-ide-search-result" onClick={() => void openFile(match.path, false, match.lineNumber || undefined)}>
                <span className="sy-ide-search-path">{match.path}</span>
                <span className="sy-ide-search-preview">{match.lineNumber ? <><b>{match.lineNumber}</b> {match.preview}</> : match.preview}</span>
              </button>
            ))}
            {!searchBusy && query.length >= 2 && searchMatches.length === 0 && <div className="sy-ide-muted-box">No matches yet. Press Enter to search.</div>}
          </div>
        </>
      );
    }

    if (sidebar === 'history') {
      return (
        <>
          <div className="sy-ide-sidebar-toolbar"><span>Git History</span><button className="sy-ide-text-button" onClick={() => void refreshHistory()} disabled={historyLoading}>{historyLoading ? 'Loading…' : 'Refresh'}</button></div>
          <div className="sy-ide-history-branch">Current branch <b>{workspace?.branch ?? '—'}</b></div>
          <div className="sy-ide-history-list" role="list" aria-label="Recent commits">
            {historyCommits.map((commit) => (
              <button key={commit.sha} className={`sy-ide-history-commit ${historyCommit?.sha === commit.sha ? 'is-selected' : ''}`}
                onClick={() => setHistoryCommit(commit)} title={`${commit.message}\n${commit.author} · ${commit.sha}`}>
                <span className="sy-ide-history-dot" />
                <span className="sy-ide-history-message">{commit.message || '(no commit message)'}</span>
                <span className="sy-ide-history-meta"><b>{commit.author}</b><span>{formatCommitDate(commit.timestamp)}</span></span>
                <code>{commit.sha.slice(0, 8)}</code>
              </button>
            ))}
            {historyLoading && <div className="sy-ide-muted-box">Loading recent commits…</div>}
            {!historyLoading && !historyCommits.length && <div className="sy-ide-muted-box">No commits found on this branch.</div>}
          </div>
          <div className="sy-ide-history-footnote">Showing up to 50 recent commits. Select one to inspect its file changes.</div>
        </>
      );
    }

    return (
      <>
        <div className="sy-ide-sidebar-toolbar"><span>Source Control</span><button className="sy-ide-text-button" onClick={() => void fetchRemote()} disabled={busy !== null}>{busy === 'fetch' ? 'Fetching…' : 'Fetch'}</button></div>
        <div className="sy-ide-git-summary">
          <div><span className="sy-ide-git-branch">⑂</span><strong>{workspace?.branch ?? '—'}</strong></div>
          <span className="sy-ide-git-sha">{workspace?.headSha.slice(0, 8) ?? ''}</span>
        </div>
        <div className="sy-ide-git-changes-heading"><span>Changes</span><span className="sy-ide-count">{workspace?.changes.length ?? 0}</span></div>
        <div className="sy-ide-git-changes">
          {workspace?.changes.map((change, index) => {
            const code = change.slice(0, 2).trim() || '?';
            const path = change.slice(3).replace(/^"|"$/g, '');
            return <button className="sy-ide-git-change" key={`${change}:${index}`} onClick={() => void openFile(path)}>
              <span className={`sy-ide-git-status ${code === '??' ? 'untracked' : ''}`}>{code}</span>
              <span>{path}</span>
            </button>;
          })}
          {!workspace?.changes.length && <div className="sy-ide-muted-box">No changes detected. Edit a file to get started.</div>}
        </div>
        <div className="sy-ide-git-actions">
          <button className="sy-ide-primary-button" onClick={() => beginDialog('commit')} disabled={busy !== null}>Commit changes</button>
          {dirtyTabs.length > 0 && <span className="sy-ide-inline-hint">Open editor changes will be saved before committing.</span>}
          <button className="sy-ide-secondary-button" onClick={() => void pushChanges()} disabled={busy !== null}>Push to origin</button>
          <p>Commits are local until pushed. Push uses the repository credential saved in Shipyard.</p>
        </div>
      </>
    );
  };

  const activeTab = tabs.find((tab) => tab.path === activePath) ?? null;
  const rightTab = tabs.find((tab) => tab.path === secondaryPath) ?? activeTab;
  const rootName = detail?.summary.name ?? 'Project';

  const onTerminalCommand = useCallback(async (command: string): Promise<TerminalCommandResult> => {
    if (!detail) return { output: 'Application is not loaded.', exitCode: -1, tone: 'error' };
    try {
      const result = await api.applications.exec(id, command, terminalCwd);
      setTerminalCwd(result.cwd);
      if (result.timedOut) return { output: result.output, exitCode: -1, tone: 'warning' };
      return { output: result.output, exitCode: result.exitCode, note: result.truncated ? 'Output truncated to 256 KB.' : undefined };
    } catch (err) {
      return { output: errorMessage(err), exitCode: -1, tone: 'error' };
    }
  }, [detail, id, terminalCwd]);

  return (
    <div className="sy-ide-page" data-editor-theme={theme} onClick={() => contextMenu && setContextMenu(null)}>
      <header className="sy-ide-topbar">
        <div className="sy-ide-brand" role="button" tabIndex={0} onClick={() => navigateSafely('/')} onKeyDown={(event) => { if (event.key === 'Enter') navigateSafely('/'); }}>
          <span className="sy-ide-brand-mark">S</span><span>Shipyard</span>
        </div>
        <span className="sy-ide-top-separator" />
        <button className="sy-ide-app-select" onClick={() => navigateSafely(`/applications/${id}`)} title="Back to application">
          <span className="sy-ide-app-symbol">▣</span><span>{rootName}</span><span className="sy-ide-app-chevron">⌄</span>
        </button>
        <span className="sy-ide-top-context">IDE</span>
        <div className="sy-ide-top-spacer" />
        <label className="sy-ide-branch-select" title="Switch project branch">
          <span>⑂</span>
          <select aria-label="Git branch" value={workspace?.branch ?? ''} onChange={(event) => void switchBranch(event.target.value)} disabled={!workspace || busy !== null}>
            {workspace?.branches.map((branch) => <option key={branch} value={branch}>{branch}</option>)}
            {workspace?.branch && !workspace.branches.includes(workspace.branch) && <option value={workspace.branch}>{workspace.branch}</option>}
          </select>
        </label>
        <div className="sy-ide-top-divider" />
        <button className="sy-ide-top-button" onClick={() => void saveAll()} disabled={busy !== null} title="Save all · Ctrl+S">
          <span>▣</span>{dirtyTabs.length ? `Save ${dirtyTabs.length}` : 'Saved'}
        </button>
        <button className="sy-ide-top-button" onClick={() => void runBuild()} disabled={busy !== null || loading} title="Build the current workspace">
          <span>{busy === 'build' ? '◌' : '▶'}</span>{busy === 'build' ? 'Building…' : 'Build'}
        </button>
        <button className="sy-ide-deploy-button" onClick={() => beginDialog('deploy')} disabled={busy !== null || loading}>
          {busy === 'deploy' ? 'Queueing…' : 'Deploy'} <span>↗</span>
        </button>
        <div className="sy-ide-user" title={session?.username ?? 'Administrator'}>
          <span className="sy-ide-user-avatar">{(session?.username ?? 'A').slice(0, 1).toUpperCase()}</span>
          <span className="sy-ide-user-name">{session?.username ?? 'admin'}</span>
        </div>
      </header>

      {error && <div className="sy-ide-error-banner" role="alert"><span>⚠ {error}</span><button onClick={() => setError(null)}>Dismiss</button></div>}

      <div className="sy-ide-workbench">
        <nav className="sy-ide-activitybar" aria-label="IDE views">
          <button className={sidebar === 'explorer' ? 'active' : ''} aria-label="Explorer" title="Explorer" onClick={() => setSidebar('explorer')}><span>▤</span></button>
          <button className={sidebar === 'search' ? 'active' : ''} aria-label="Search" title="Search in files" onClick={openQuickSearch}><span>⌕</span></button>
          <button className={sidebar === 'git' ? 'active' : ''} aria-label="Source control" title="Source control" onClick={() => setSidebar('git')}><span>⑂</span>{workspace?.changes.length ? <i /> : null}</button>
          <button className={sidebar === 'history' ? 'active' : ''} aria-label="Git history" title="Git history" onClick={() => setSidebar('history')}><span>◷</span></button>
          <div className="sy-ide-activity-spacer" />
          <button aria-label="Settings" title="Editor settings" onClick={() => setTheme((current) => current === 'vs-dark' ? 'vs' : 'vs-dark')}><span>⚙</span></button>
        </nav>

        <aside className="sy-ide-sidebar">
          {renderSidebar()}
        </aside>

        <section className="sy-ide-editor-column">
          <div className="sy-ide-tabbar" role="tablist" aria-label="Open files">
            <div className="sy-ide-tabs">
              {tabs.map((tab) => (
                <div key={tab.path} role="presentation" className={`sy-ide-tab ${activePath === tab.path ? 'active' : ''}`}>
                  <button role="tab" aria-selected={activePath === tab.path} className="sy-ide-tab-label" onClick={() => setActivePath(tab.path)} title={tab.path}>
                    <span className={`sy-ide-file-glyph ${languageFor(tab.path)}`}>{fileGlyph(leafName(tab.path))}</span>
                    <span>{leafName(tab.path)}</span>
                    {isDirty(tab) && <span className="sy-ide-dirty-dot" aria-label="Unsaved changes" />}
                  </button>
                  <button className="sy-ide-tab-close" aria-label={`Close ${leafName(tab.path)}`} onClick={() => closeTab(tab.path)}>×</button>
                </div>
              ))}
            </div>
            <div className="sy-ide-editor-actions">
              {activeTab && <span className="sy-ide-breadcrumb">{activeTab.path.split('/').slice(0, -1).join(' / ')}{activeTab.path.includes('/') ? ' / ' : ''}<b>{leafName(activeTab.path)}</b></span>}
              <button title="Find in file · Ctrl+F" onClick={() => editorRefs.current.left?.trigger('keyboard', 'actions.find', null)}>⌕</button>
              <button title="Replace in file · Ctrl+H" onClick={() => editorRefs.current.left?.trigger('keyboard', 'editor.action.startFindReplaceAction', null)}>⇄</button>
              <button title={split ? 'Close split editor' : 'Split editor'} onClick={() => {
                if (split) { setSplit(false); setSecondaryPath(null); }
                else { setSplit(true); setSecondaryPath(activePath); }
              }}>{split ? '◧' : '◫'}</button>
              <button title="Format document" onClick={() => { void editorRefs.current.left?.getAction('editor.action.formatDocument')?.run(); }}>⌘</button>
              <button title="Toggle bottom panel" onClick={() => setBottomOpen((open) => !open)}>▱</button>
            </div>
          </div>

          <div className={`sy-ide-editor-area ${split && sidebar !== 'history' ? 'is-split' : ''}`}>
            {loading ? (
              <div className="sy-ide-welcome"><div className="sy-ide-loading-spinner" /><strong>Preparing workspace…</strong><span>Loading project files and Git branch.</span></div>
            ) : sidebar === 'history' ? (
              <div className="sy-ide-history-inspector">
                {historyCommit ? (
                  <>
                    <div className="sy-ide-history-inspector-header">
                      <div>
                        <span className="sy-ide-history-kicker">COMMIT · {historyCommit.sha.slice(0, 12)}</span>
                        <h2>{historyCommit.message || '(no commit message)'}</h2>
                        <p>{historyCommit.author} · {formatCommitDate(historyCommit.timestamp)}</p>
                      </div>
                      <button className="sy-ide-text-button" onClick={() => setSidebar('explorer')}>Return to editor</button>
                    </div>
                    <div className="sy-ide-history-inspector-body">
                      <aside className="sy-ide-history-files">
                        <div className="sy-ide-history-files-heading">Changed files <span>{historyFiles.length}</span></div>
                        {historyFiles.map((file) => (
                          <button key={file.path} className={`sy-ide-history-file ${selectedHistoryPath === file.path ? 'is-selected' : ''}`}
                            onClick={() => setSelectedHistoryPath(file.path)} title={file.path}>
                            <span className={`sy-ide-history-file-status ${file.status.toLowerCase().replace(/[^a-z]/g, '')}`}>{file.status}</span>
                            <span>{file.path}</span>
                          </button>
                        ))}
                        {historyFilesLoading && <div className="sy-ide-muted-box">Loading changed files…</div>}
                        {!historyFilesLoading && !historyFiles.length && <div className="sy-ide-muted-box">No file changes were found in this commit.</div>}
                        {historyFilesTruncated && <div className="sy-ide-truncated">Showing the first 500 files.</div>}
                      </aside>
                      <section className="sy-ide-history-diff">
                        <div className="sy-ide-history-diff-heading"><span>{selectedHistoryPath ?? 'Select a changed file'}</span>
                          {historyFiles.find((file) => file.path === selectedHistoryPath)?.status && <code>{historyFiles.find((file) => file.path === selectedHistoryPath)?.status}</code>}
                        </div>
                        {historyDiffLoading && <div className="sy-ide-panel-empty"><div className="sy-ide-loading-spinner" /><span>Loading diff…</span></div>}
                        {!historyDiffLoading && historyDiff && (
                          <>
                            {historyDiff.truncated && <div className="sy-ide-diff-truncated">Diff limited to 1 MB of output.</div>}
                            {historyDiff.diff ? <pre className="sy-ide-diff-code"><code>{historyDiff.diff.split('\n').map((line, index) => (
                              <span key={index} className={`sy-ide-diff-line ${diffLineClass(line)}`}>{line || '\u00a0'}{'\n'}</span>
                            ))}</code></pre> : <div className="sy-ide-panel-empty"><strong>No text diff available</strong><span>The file may be binary or have no printable changes.</span></div>}
                          </>
                        )}
                        {!historyDiffLoading && !historyDiff && !selectedHistoryPath && <div className="sy-ide-panel-empty"><strong>Choose a changed file</strong><span>Select a file on the left to inspect its patch.</span></div>}
                      </section>
                    </div>
                  </>
                ) : (
                  <div className="sy-ide-panel-empty"><strong>{historyLoading ? 'Loading commit history…' : 'No commit selected'}</strong><span>Select a recent commit from the Git History list.</span></div>
                )}
              </div>
            ) : activeTab ? (
              <>
                <div className="sy-ide-editor-pane" onFocus={() => {}}>
                  <EditorPane key={`left:${activeTab.path}`} tab={activeTab} pane="left" theme={theme} tabSize={tabSize} onChange={updateContent} onMount={onEditorMount} onUnmount={onEditorUnmount} onValidate={handleValidate} />
                </div>
                {split && (
                  <div className="sy-ide-editor-pane sy-ide-editor-pane-right">
                    {rightTab ? <EditorPane key={`right:${rightTab.path}`} tab={rightTab} pane="right" theme={theme} tabSize={tabSize} onChange={updateContent} onMount={onEditorMount} onUnmount={onEditorUnmount} onValidate={handleValidate} />
                      : <div className="sy-ide-empty-editor">Open a file to the side from Explorer with Shift-click.</div>}
                  </div>
                )}
              </>
            ) : (
              <div className="sy-ide-welcome">
                <div className="sy-ide-welcome-logo">S</div>
                <h1>{rootName}</h1>
                <p>Shipyard IDE · a focused workspace for your application</p>
                <div className="sy-ide-welcome-grid">
                  <button onClick={() => beginDialog('new-file')}><kbd>＋</kbd><span>New file</span><small>Create a source file</small></button>
                  <button onClick={openQuickOpen}><kbd>⌘ P</kbd><span>Open file</span><small>Quickly navigate the project</small></button>
                  <button onClick={() => { setBottomView('terminal'); setBottomOpen(true); }}><kbd>⌃ `</kbd><span>Open terminal</span><small>Run commands in the current deployment</small></button>
                  <button onClick={() => void runBuild()}><kbd>▶</kbd><span>Build workspace</span><small>Check the current source</small></button>
                </div>
                <div className="sy-ide-welcome-meta"><span>Branch <b>{workspace?.branch ?? '—'}</b></span><span>HEAD <b>{workspace?.headSha.slice(0, 8) ?? '—'}</b></span></div>
              </div>
            )}
          </div>

          {bottomOpen && (
            <div className="sy-ide-bottom-panel" style={{ height: bottomHeight }}>
              <div className="sy-ide-resize-handle" onMouseDown={startPanelResize} title="Drag to resize panel" />
              <div className="sy-ide-bottom-header">
                <div className="sy-ide-bottom-tabs" role="tablist" aria-label="Bottom panel">
                  {(['terminal', 'problems', 'output', 'debug'] as BottomView[]).map((view) => (
                    <button key={view} role="tab" aria-selected={bottomView === view} className={bottomView === view ? 'active' : ''} onClick={() => setBottomView(view)}>
                      {view === 'terminal' ? 'Terminal' : view === 'problems' ? <>Problems{allProblems.length > 0 && <span className="sy-ide-panel-badge">{allProblems.length}</span>}</> : view === 'output' ? 'Output' : <>Debug{allBreakpoints.length > 0 && <span className="sy-ide-panel-badge">{allBreakpoints.length}</span>}</>}
                    </button>
                  ))}
                </div>
                <div className="sy-ide-bottom-tools">
                  {bottomView === 'terminal' && <span className="sy-ide-terminal-target">{terminalEnabled ? `DEPLOYED · ${currentDeployment?.containerName ?? 'container'}` : 'NO RUNNING SERVER'}</span>}
                  {bottomView === 'output' && <button onClick={() => { setOutput([]); setBuildResult(null); }}>Clear output</button>}
                  <button title="Close panel" onClick={() => setBottomOpen(false)}>×</button>
                </div>
              </div>
              <div className="sy-ide-panel-body">
                {bottomView === 'terminal' && (terminalEnabled ? (
                  <>
                    <div className="sy-ide-terminal-notice">This terminal is connected to the <b>currently deployed container</b>. File edits stay in the workspace until you build and deploy.</div>
                    <Terminal
                      key={currentDeployment?.id ?? id}
                      className="sy-ide-terminal"
                      prompt={`${terminalCwd ?? '~'} $`}
                      onCommand={onTerminalCommand}
                      history={terminalHistory}
                      onHistoryChange={setTerminalHistory}
                      emptyMessage="Type a command to run it in the deployed container. Interactive programs are not supported."
                      height="100%"
                      maxEntries={200}
                      downloadFileName={`${rootName}-ide-terminal.log`}
                    />
                  </>
                ) : <div className="sy-ide-panel-empty"><strong>No deployed server terminal</strong><span>Deploy a server application to run commands in its container. Static apps are served directly and do not have a shell.</span></div>)}
                {bottomView === 'problems' && (
                  <div className="sy-ide-problems-list">
                    {allProblems.map(({ path, marker }, index) => (
                      <button key={`${path}:${marker.startLineNumber}:${index}`} className={`sy-ide-problem ${markerSeverity(marker.severity)}`} onClick={() => void openFile(path, false, marker.startLineNumber)}>
                        <span>{marker.severity >= 8 ? '×' : marker.severity >= 4 ? '⚠' : 'ⓘ'}</span><b>{marker.message}</b><small>{path}:{marker.startLineNumber}:{marker.startColumn}</small>
                      </button>
                    ))}
                    {!allProblems.length && <div className="sy-ide-panel-empty"><strong>No problems detected</strong><span>Diagnostics appear here for language services enabled in Monaco.</span></div>}
                  </div>
                )}
                {bottomView === 'output' && (
                  <div className="sy-ide-output-view">
                    {buildResult && <div className={`sy-ide-build-result ${buildResult.success ? 'success' : 'failure'}`}>
                      <b>{buildResult.success ? 'Build passed' : 'Build failed'}</b><span>{buildResult.framework ?? buildResult.projectType ?? 'Workspace'} · {(buildResult.durationMs / 1000).toFixed(1)}s</span>
                    </div>}
                    {output.length ? output.map((line, index) => <div className="sy-ide-output-line" key={`${index}:${line}`}>{line}</div>) : <div className="sy-ide-panel-empty"><strong>Output is empty</strong><span>Builds, commits, pushes, and deployments will be shown here.</span></div>}
                  </div>
                )}
                {bottomView === 'debug' && (
                  <div className="sy-ide-debug-view">
                    <div className="sy-ide-debug-toolbar"><button disabled title="No debug adapter configured">▶ Continue</button><button disabled title="No debug adapter configured">↷ Step over</button><button disabled title="No debug adapter configured">■ Stop</button><span>No debug adapter configured for this project.</span></div>
                    <div className="sy-ide-debug-title">BREAKPOINTS <span>{allBreakpoints.length}</span></div>
                    {allBreakpoints.map((item) => <button key={`${item.path}:${item.line}`} className="sy-ide-breakpoint-row" onClick={() => void openFile(item.path, false, item.line)}><span className="sy-ide-breakpoint-dot" />{item.path}<b>:{item.line}</b></button>)}
                    {!allBreakpoints.length && <div className="sy-ide-panel-empty"><strong>No breakpoints</strong><span>Click a line's gutter in the editor to add a breakpoint.</span></div>}
                  </div>
                )}
              </div>
            </div>
          )}
        </section>
      </div>

      <footer className="sy-ide-statusbar">
        <div><span className="sy-ide-status-branch">⑂ {workspace?.branch ?? '—'}</span><span>{workspace?.changes.length ?? 0} changes</span><span>HEAD {workspace?.headSha.slice(0, 8) ?? '—'}</span></div>
        <span className={`sy-ide-status-message ${error ? 'error' : ''}`}>{notice}</span>
        <div className="sy-ide-status-settings">
          <label title="Editor tab width">Tab size <select aria-label="Tab size" value={tabSize} onChange={(event) => setTabSize(Number(event.target.value))}><option value={2}>2</option><option value={4}>4</option><option value={8}>8</option></select></label>
          <label title="Format supported files before saving"><input type="checkbox" checked={formatOnSave} onChange={(event) => setFormatOnSave(event.target.checked)} /> Format on save</label>
          <button onClick={() => setTheme((current) => current === 'vs-dark' ? 'vs' : current === 'vs' ? 'hc-black' : 'vs-dark')} title="Cycle editor theme">{theme === 'vs-dark' ? 'Dark' : theme === 'hc-black' ? 'High contrast' : 'Light'}</button>
          {activeTab && <span>{languageFor(activeTab.path).toUpperCase()}</span>}
          {activeTab && <span>UTF-8</span>}
        </div>
      </footer>

      {loading && <div className="sy-ide-loading-overlay" aria-live="polite"><div className="sy-ide-loading-card"><div className="sy-ide-loading-spinner" /><strong>Preparing {rootName}</strong><span>Initializing a safe editable worktree.</span></div></div>}

      {contextMenu && (
        <div className="sy-ide-context-backdrop" onClick={() => setContextMenu(null)}>
          <div className="sy-ide-context-menu" style={{ left: contextMenu.x, top: contextMenu.y }} onClick={(event) => event.stopPropagation()} role="menu">
            {contextMenu.entry.kind === 'FILE' && <button role="menuitem" onClick={() => { void openFile(contextMenu.entry.path); setContextMenu(null); }}>Open</button>}
            {contextMenu.entry.kind === 'FILE' && <button role="menuitem" onClick={() => { void openFile(contextMenu.entry.path, true); setContextMenu(null); }}>Open to the side</button>}
            {contextMenu.entry.kind === 'FILE' && <button role="menuitem" onClick={() => { void downloadFile(contextMenu.entry.path); setContextMenu(null); }}>Download</button>}
            <button role="menuitem" onClick={() => { const dir = contextMenu.entry.kind === 'DIRECTORY' ? contextMenu.entry.path : parentPath(contextMenu.entry.path); setSelectedDirectory(dir); beginDialog('new-file', dir); }}>New file…</button>
            <button role="menuitem" onClick={() => { const dir = contextMenu.entry.kind === 'DIRECTORY' ? contextMenu.entry.path : parentPath(contextMenu.entry.path); setSelectedDirectory(dir); beginDialog('new-folder', dir); }}>New folder…</button>
            {contextMenu.entry.path && <><div className="sy-ide-context-divider" /><button role="menuitem" onClick={() => beginDialog('rename', contextMenu.entry.path)}>Rename…</button>
              <button role="menuitem" onClick={() => beginDialog('copy', contextMenu.entry.path)}>Copy to…</button>
              <button role="menuitem" onClick={() => beginDialog('move', contextMenu.entry.path)}>Move to…</button>
              <button role="menuitem" className="destructive" onClick={() => beginDialog('delete', contextMenu.entry.path)}>Delete…</button></>}
          </div>
        </div>
      )}

      {quickOpen && (
        <div className="sy-ide-modal-backdrop" onClick={() => setQuickOpen(false)}>
          <div className="sy-ide-quick-open" onClick={(event) => event.stopPropagation()}>
            <div className="sy-ide-quick-input"><span>⌕</span><input ref={quickInputRef} value={quickQuery} onChange={(event) => setQuickQuery(event.target.value)} placeholder="Search files by name…" onKeyDown={(event) => { if (event.key === 'Escape') setQuickOpen(false); if (event.key === 'Enter' && filteredQuickFiles[0]) { void openFile(filteredQuickFiles[0].path); setQuickOpen(false); } }} /></div>
            <div className="sy-ide-quick-list">
              {filteredQuickFiles.map((entry) => <button key={entry.path} onClick={() => { void openFile(entry.path); setQuickOpen(false); }}><span className={`sy-ide-file-glyph ${languageFor(entry.path)}`}>{fileGlyph(leafName(entry.path))}</span><span>{leafName(entry.path)}</span><small>{entry.path}</small></button>)}
              {quickFilesLoading && <div className="sy-ide-muted-box">Indexing project files…</div>}
              {!quickFilesLoading && !filteredQuickFiles.length && <div className="sy-ide-muted-box">No project files match this name.</div>}
            </div>
            <div className="sy-ide-quick-hint">Type to filter · Enter to open · Esc to close{quickFilesTruncated ? ' · file list limited to 10,000' : ''}</div>
          </div>
        </div>
      )}

      {dialog && (
        <div className="sy-ide-modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setDialog(null); }}>
          <form className="sy-ide-dialog" onSubmit={(event) => { event.preventDefault(); void runDialogAction(); }}>
            <div className="sy-ide-dialog-heading">
              <div><span className="sy-ide-dialog-kicker">{rootName}</span><h2>{dialog.kind === 'new-file' ? 'Create file' : dialog.kind === 'new-folder' ? 'Create folder' : dialog.kind === 'rename' ? 'Rename' : dialog.kind === 'copy' ? 'Copy item' : dialog.kind === 'move' ? 'Move item' : dialog.kind === 'delete' ? 'Delete item' : dialog.kind === 'commit' ? 'Commit changes' : 'Deploy workspace'}</h2></div>
              <button type="button" className="sy-ide-dialog-close" onClick={() => setDialog(null)} aria-label="Close dialog">×</button>
            </div>
            {dialog.kind === 'delete' ? (
              <p className="sy-ide-delete-warning">Delete <b>{dialog.path}</b> and its contents? This cannot be undone.</p>
            ) : (
              <label className="sy-ide-dialog-label">{dialog.kind === 'commit' ? 'Commit message' : dialog.kind === 'deploy' ? 'Deployment note' : dialog.kind === 'rename' ? 'New name' : dialog.kind === 'copy' || dialog.kind === 'move' ? 'Project-relative destination path' : 'Project-relative path'}
                <input autoFocus value={dialogValue} onChange={(event) => setDialogValue(event.target.value)} placeholder={dialog.kind === 'rename' ? 'File name' : 'src/new-file.ts'} />
              </label>
            )}
            <div className="sy-ide-dialog-scope"><span>⌑</span> Operations are limited to this application's workspace. Git metadata and symbolic links are blocked.</div>
            <div className="sy-ide-dialog-actions"><button type="button" className="sy-ide-secondary-button" onClick={() => setDialog(null)}>Cancel</button><button type="submit" className={dialog.kind === 'delete' ? 'sy-ide-danger-button' : 'sy-ide-primary-button'} disabled={busy !== null}>{dialog.kind === 'delete' ? 'Delete' : dialog.kind === 'commit' ? 'Commit' : dialog.kind === 'deploy' ? 'Queue deployment' : 'Continue'}</button></div>
          </form>
        </div>
      )}

      {!bottomOpen && <button className="sy-ide-panel-reopen" onClick={() => setBottomOpen(true)}>Show panel</button>}
    </div>
  );
}
