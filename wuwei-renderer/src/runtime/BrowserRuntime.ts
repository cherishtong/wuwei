import type { HandlerFunction, BrowserCapability } from './types';
import { createLocalCapabilities } from './LocalCapabilities';
import { createProxyCapabilities, initProxyListener } from './ProxyCapabilities';
import { createThreeJsCapability } from './ThreeJsCapability';

interface RuntimeEntry {
  skillId: string;
  handlers: Record<string, HandlerFunction>;
  declaredCaps: Record<string, unknown>;
  componentCache: Record<string, Record<string, unknown>>;
  patchBuffer: Record<string, unknown>[];
  applyPatches: (skillId: string, patches: Record<string, unknown>[]) => void;
}

const entries = new Map<string, RuntimeEntry>();
let proxyListenerInitialized = false;

function eventToHandler(eventId: string): string {
  if (eventId === '__init__') return 'onInit';
  // Convert kebab-case to camelCase with 'on' prefix
  const camel = eventId.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase());
  return 'on' + camel.charAt(0).toUpperCase() + camel.slice(1);
}

/**
 * Parse handlers.js and create sandboxed handler functions.
 * Uses a Function-scope sandbox where dangerous globals are shadowed.
 */
function parseHandlers(
  code: string,
  buildCapability: (entry: RuntimeEntry) => BrowserCapability,
  entry: RuntimeEntry
): Record<string, HandlerFunction> {
  const handlers: Record<string, HandlerFunction> = {};

  // Extract handler names via regex
  const handlerNames: string[] = [];
  const regex = /(?:async\s+)?function\s+(on\w+)\s*\(/g;
  let m: RegExpExecArray | null;
  while ((m = regex.exec(code)) !== null) {
    handlerNames.push(m[1]);
  }

  if (handlerNames.length === 0) return handlers;

  // Build sandbox: shadow dangerous globals.
  // window is replaced with a filtered proxy that only allows __wuwei_*
  // bridge properties plus a few safe browser APIs needed by canvas handlers.
  const shadowedGlobals = [
    'document', 'fetch', 'XMLHttpRequest', 'WebSocket', 'Worker',
    'globalThis', 'global', 'process', 'require', 'module', 'exports',
    '__dirname', '__filename',
  ];

  const allowListedGlobalProps = [
    '__wuwei_',          // canvas / threejs resize bridges
    'devicePixelRatio',
    'ResizeObserver',
    'requestAnimationFrame',
    'cancelAnimationFrame',
  ];

  // Create a function that shadows globals and returns handler references
  const sandboxCode = `
    return (function() {
      ${shadowedGlobals.map((k) => `var ${k} = undefined;`).join('\n      ')}
      // Safe document proxy for canvas/3D skills — uses captured realDoc reference
      var realDoc = __sandbox_document__;
      var document = new Proxy({}, {
        get: function(__tgt, __prop) {
          if (__prop === 'getElementById') return function(id) { return realDoc.getElementById(id); };
          if (__prop === 'createElement') return function(tag) { return realDoc.createElement(tag); };
          if (__prop === 'createElementNS') return function(ns, tag) { return realDoc.createElementNS(ns, tag); };
          if (__prop === 'body') return realDoc.body;
          if (__prop === 'head') return realDoc.head;
          if (__prop === 'querySelector') return function(sel) { return realDoc.querySelector(sel); };
          return undefined;
        },
        set: function() { return true; }
      });
      // Safe window proxy for browser APIs needed by canvas/3D skills
      var window = new Proxy((function(){return this;})() || {}, {
        get: function(__tgt, __prop) {
          var s = String(__prop);
          var allowed = ${JSON.stringify(allowListedGlobalProps)};
          for (var i = 0; i < allowed.length; i++) {
            if (s.indexOf(allowed[i]) === 0) return __tgt[__prop];
          }
          return undefined;
        },
        set: function() { return true; }
      });
      // Provide T and THREE globals — populated via __setT__ before handler calls
      var T = null;
      var THREE = null;
      ${code}
      return { __setT__: function(t) { T = t; THREE = t; }, ${handlerNames.join(', ')} };
    })();
  `;

  try {
    const factory = new Function('__sandbox_document__', sandboxCode);
    const result = factory(document);

    for (const name of handlerNames) {
      if (typeof result[name] === 'function') {
        const originalFn = result[name];
        // Wrap to inject capability
        handlers[name] = (inputs: Record<string, unknown>, _cap?: BrowserCapability) => {
          const cap = _cap ?? buildCapability(entry);
          // Inject T (THREE) global for 3D skills
          if (result.__setT__ && cap.threejs) result.__setT__(cap.threejs);
          return originalFn(inputs, cap);
        };
      }
    }
  } catch (e) {
    console.error('[BrowserRuntime] Failed to parse handlers:', e);
  }

  return handlers;
}

function buildCapability(entry: RuntimeEntry): BrowserCapability {
  const local = createLocalCapabilities(
    entry.skillId,
    entry.componentCache,
    entry.patchBuffer,
    entry.declaredCaps,
    entry.applyPatches,
  );
  const proxy = createProxyCapabilities(entry.skillId, entry.declaredCaps);
  const threejs = createThreeJsCapability(entry.skillId);
  return { ...local, ...proxy, ...threejs } as BrowserCapability;
}

export class BrowserRuntime {
  load(
    skillId: string,
    handlersJs: string,
    declaredCaps: Record<string, unknown>,
    applyPatches: (skillId: string, patches: Record<string, unknown>[]) => void
  ) {
    if (!proxyListenerInitialized) {
      initProxyListener();
      proxyListenerInitialized = true;
    }

    const entry: RuntimeEntry = {
      skillId,
      handlers: {},
      declaredCaps,
      componentCache: {},
      patchBuffer: [],
      applyPatches,
    };

    entry.handlers = parseHandlers(handlersJs, buildCapability, entry);
    entries.set(skillId, entry);
    console.log('[BrowserRuntime] Loaded:', skillId, 'handlers:', Object.keys(entry.handlers));
  }

  handleEvent(skillId: string, eventId: string, inputs: Record<string, unknown>) {
    const entry = entries.get(skillId);
    if (!entry) {
      console.warn('[BrowserRuntime] No entry for:', skillId);
      return;
    }

    const handlerName = eventToHandler(eventId);
    const handler = entry.handlers[handlerName];
    if (!handler) {
      console.warn('[BrowserRuntime] No handler:', handlerName, 'for event:', eventId);
      return;
    }

    // Reset patch buffer for this event
    entry.patchBuffer.length = 0;

    try {
      const result = handler(inputs);
      // Flush patches after handler returns
      if (result instanceof Promise) {
        result.then(() => this.flushPatches(entry)).catch((e) => {
          console.error('[BrowserRuntime] Handler error:', e);
          // Flush any patches that were set before the error
          try { this.flushPatches(entry); } catch (_) {}
        });
      } else {
        this.flushPatches(entry);
      }
    } catch (e) {
      console.error('[BrowserRuntime] Handler threw:', e);
      this.flushPatches(entry);
    }
  }

  private flushPatches(entry: RuntimeEntry) {
    if (entry.patchBuffer.length > 0) {
      const patches = [...entry.patchBuffer];
      entry.patchBuffer.length = 0;
      entry.applyPatches(entry.skillId, patches);
    }
  }

  unload(skillId: string) {
    entries.delete(skillId);
    console.log('[BrowserRuntime] Unloaded:', skillId);
  }

  isActive(skillId: string): boolean {
    return entries.has(skillId);
  }
}
