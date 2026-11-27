/**
 * The frontend runs either against the Shipyard API (normal) or, on the
 * GitHub Pages demo, against a simulated in-browser backend. The demo uses
 * hash routing because static hosting cannot serve client-side routes.
 */
import type { ComponentType } from 'react';

export const platformMode: { demo: boolean; Banner?: ComponentType } = { demo: false };

/** href for an in-app route, matching the active router. */
export const appHref = (path: string) => (platformMode.demo ? `#${path}` : path);
