import EditorWorker from '../node_modules/monaco-editor/esm/vs/editor/editor.worker.js?worker';
import JsonWorker from '../node_modules/monaco-editor/esm/vs/language/json/json.worker.js?worker';
import CssWorker from '../node_modules/monaco-editor/esm/vs/language/css/css.worker.js?worker';
import HtmlWorker from '../node_modules/monaco-editor/esm/vs/language/html/html.worker.js?worker';
import TypeScriptWorker from '../node_modules/monaco-editor/esm/vs/language/typescript/ts.worker.js?worker';

type MonacoWorkerEnvironment = { getWorker: (_moduleId: string, label: string) => Worker };
type MonacoInstance = Parameters<import('@monaco-editor/react').OnMount>[1];

// Monaco does not have access to the repository's node_modules. This minimal
// ambient declaration keeps automatic React JSX working in the editor until
// project-specific type packages can be loaded into the language service.
const JSX_RUNTIME_TYPES = `
declare module "react/jsx-runtime" {
  export namespace JSX {
    interface Element {}
    interface IntrinsicElements { [elementName: string]: any; }
    interface IntrinsicAttributes { key?: string | number; }
    interface ElementChildrenAttribute { children: {}; }
  }
  export const Fragment: unique symbol;
  export function jsx(type: any, props: any, key?: string | number): JSX.Element;
  export function jsxs(type: any, props: any, key?: string | number): JSX.Element;
}
`;
let jsxRuntimeTypesRegistered = false;

export function configureMonacoTypeServices(monaco: MonacoInstance): void {
  if (jsxRuntimeTypesRegistered) return;
  const declarationPath = 'file:///workspace/__shipyard_react_jsx_runtime.d.ts';
  monaco.languages.typescript.typescriptDefaults.addExtraLib(JSX_RUNTIME_TYPES, declarationPath);
  monaco.languages.typescript.javascriptDefaults.addExtraLib(JSX_RUNTIME_TYPES, declarationPath);
  jsxRuntimeTypesRegistered = true;
}

// Monaco uses web workers for TypeScript/JavaScript, JSON, CSS and HTML language services.
(globalThis as typeof globalThis & { MonacoEnvironment?: MonacoWorkerEnvironment }).MonacoEnvironment = {
  getWorker(_moduleId, label) {
    if (label === 'json') return new JsonWorker();
    if (label === 'css' || label === 'scss' || label === 'less') return new CssWorker();
    if (label === 'html' || label === 'handlebars' || label === 'razor') return new HtmlWorker();
    if (label === 'typescript' || label === 'javascript') return new TypeScriptWorker();
    return new EditorWorker();
  },
};
