import { useRef, useState, useEffect, useLayoutEffect } from 'react';
import { MessageProcessor } from '@a2ui/web_core/v0_9';
import type { SurfaceModel, ActionListener } from '@a2ui/web_core/v0_9';
import { A2uiSurface, MarkdownContext } from '@a2ui/react/v0_9';
import { renderMarkdown } from '@a2ui/markdown-it';
import { wvCatalog } from '@/wv-components/a2ui';
import { kernel } from '../kernel';
import { surfaceStore } from '../stores/SurfaceStore';
import { BrowserRuntime } from '../runtime/BrowserRuntime';

const CATALOG_ID = 'https://a2ui.org/specification/v0_9/basic_catalog.json';

interface ThreadSurfaceState {
  skillId: string | null;
  componentTypeMap: Map<string, string>;
  isBrowserJs: boolean;
  browserRuntime: BrowserRuntime;
}

interface WwWorkspaceProps {
  activeThreadId?: string | null;
  initDetail?: Record<string, unknown> | null;
}

export function WwWorkspace({ activeThreadId, initDetail }: WwWorkspaceProps) {
  const [surfaceIds, setSurfaceIds] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [debugError, setDebugError] = useState<string | null>(null);
  const surfaceMapRef = useRef(new Map<string, SurfaceModel>());
  const activeThreadIdRef = useRef<string | null>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const processorRef = useRef<MessageProcessor<any> | null>(null);
  const dedupRef = useRef({ key: '', time: 0 });
  const initializedRef = useRef<string | null>(null);

  const threadStates = useRef(new Map<string, ThreadSurfaceState>());
  const surfaceThreadMap = useRef(new Map<string, string>());

  // Keep activeThreadIdRef in sync with prop (sync before paint so
  // initDetail replay and event handlers see the correct thread key)
  useLayoutEffect(() => {
    activeThreadIdRef.current = activeThreadId ?? null;
  }, [activeThreadId]);

  function getThreadKey(): string {
    return activeThreadIdRef.current ?? '__global__';
  }

  function threadMatches(eventThreadId: string | null | undefined): boolean {
    // Guard against string "null" from kernel (Jackson NullNode.asText() bug)
    const tid = (eventThreadId && eventThreadId !== 'null') ? eventThreadId : null;
    const eventKey = tid || '__global__';
    const myKey = getThreadKey();
    return eventKey === myKey || eventKey === '__global__' || myKey === '__global__';
  }

  function getCurrentThreadState(): ThreadSurfaceState {
    const key = getThreadKey();
    let state = threadStates.current.get(key);
    if (!state) {
      state = {
        skillId: null,
        componentTypeMap: new Map(),
        isBrowserJs: false,
        browserRuntime: new BrowserRuntime(),
      };
      threadStates.current.set(key, state);
    }
    return state;
  }

  function loadSkill(skillId: string, ui: Record<string, unknown>) {
    const threadKey = getThreadKey();
    const state = getCurrentThreadState();
    state.skillId = skillId;
    setLoading(true);
    setDebugError(null);
    clearSurfaces();

    // Diagnostic: inspect ui structure
    const uiType = typeof ui;
    const uiKeys = ui ? Object.keys(ui) : [];
    const componentsRaw = ui?.components;
    const compType = Array.isArray(componentsRaw) ? 'array' : typeof componentsRaw;
    let components = (ui?.components as Record<string, unknown>[]) ?? [];
    if (!Array.isArray(componentsRaw) || components.length === 0) {
      setDebugError(
        `ui type: ${uiType}\n` +
        `ui keys: [${uiKeys.join(', ')}]\n` +
        `ui.components type: ${compType}\n` +
        `ui.components value: ${JSON.stringify(componentsRaw)?.substring(0, 200)}\n` +
        `skillId: ${skillId}\n` +
        `threadKey: ${threadKey}`
      );
      setLoading(false);
      return;
    }
    // ... rest of function

    const typeMap = new Map<string, string>();
    for (const comp of components) {
      const cid = comp['id'] as string | undefined;
      if (cid) typeMap.set(cid, (comp['component'] as string) || '');
    }
    state.componentTypeMap = typeMap;

    const dmInit: Record<string, unknown> = {};
    for (const comp of components) {
      if (comp['component'] === 'TextField' || comp['component'] === 'CheckBox') {
        const val = comp['value'] as Record<string, unknown> | undefined;
        if (val?.path && typeof val.path === 'string') {
          const key = val.path.replace(/^\//, '');
          if (!(key in dmInit)) dmInit[key] = comp['component'] === 'CheckBox' ? false : '';
        }
      }
    }

    const surfaceId = threadKey === '__global__' ? 'main' : threadKey;

    const processor = processorRef.current!;
    try {
      processor.processMessages([
        { createSurface: { surfaceId, catalogId: CATALOG_ID } },
      ] as never);
      surfaceThreadMap.current.set(surfaceId, threadKey);
      processor.processMessages([
        { updateDataModel: { surfaceId, path: '/', value: dmInit } },
      ] as never);
      if (components.length > 0) {
        processor.processMessages([
          { updateComponents: { surfaceId, components } },
        ] as never);
      }
      // Diagnostic: verify root component exists after processing
      const surface = surfaceMapRef.current.get(surfaceId);
      if (surface) {
        const rootComp = surface.componentsModel.get('root');
        if (!rootComp) {
          const allIds: string[] = [];
          for (const [id] of surface.componentsModel.entries) {
            allIds.push(id);
          }
          setDebugError(`BUG: root component missing after loadSkill!\nsurfaceId=${surfaceId}\ncomponents passed=${components.length}\nmodel components=[${allIds.join(', ')}]`);
        }
      }
    } catch (e) {
      setDebugError(`loadSkill error: ${String(e)}\n\nskillId=${skillId}\nsurfaceId=${surfaceId}\ncomponentCount=${components.length}`);
    }
  }

  function clearSurfaces() {
    const processor = processorRef.current;
    if (!processor) return;
    const ids = Array.from(surfaceMapRef.current.keys());
    console.log('[ww-workspace] clearSurfaces: clearing', ids);
    for (const id of ids) {
      try {
        processor.processMessages([
          { deleteSurface: { surfaceId: id } },
        ] as never);
        console.log('[ww-workspace] clearSurfaces: deleted surface', id);
      } catch (e) { console.error('[ww-workspace] clearSurfaces error:', e); }
    }
    surfaceMapRef.current.clear();
    surfaceThreadMap.current.clear();
    setSurfaceIds([]);
  }

  function applyPatches(skillId: string, threadId: string | null, patches: Record<string, unknown>[]) {
    if (!threadMatches(threadId)) return;
    if (!patches?.length) return;

    // Use our own thread key for surfaceId (not the event's),
    // because the surface was created with getThreadKey().
    const myThreadKey = getThreadKey();
    const surfaceId = myThreadKey === '__global__' ? 'main' : myThreadKey;
    const processor = processorRef.current!;
    const state = getCurrentThreadState();

    const dataPatches: { path: string; value: unknown }[] = [];
    const compPatches: Record<string, unknown>[] = [];

    const surface = surfaceMapRef.current.get(surfaceId);
    for (const p of patches) {
      if (p['type'] === 'data') {
        dataPatches.push({ path: p['path'] as string, value: p['value'] });
      } else {
        const compId = p['id'] as string;
        let compType = p['component'] as string | undefined;
        if (!compType) {
          compType = state.componentTypeMap.get(compId);
        }
        const existing = surface?.componentsModel.get(compId);
        const base = existing?.properties ?? {};
        const merged: Record<string, unknown> = { ...base };
        for (const [k, v] of Object.entries(p)) {
          if (k !== 'id' && k !== 'component') {
            merged[k] = v;
          }
        }
        compPatches.push({ id: compId, component: compType ?? existing?.type ?? 'unknown', ...merged });
      }
    }

    try {
      for (const dp of dataPatches) {
        processor.processMessages([
          { updateDataModel: { surfaceId, path: dp.path, value: dp.value } },
        ] as never);
      }
      if (compPatches.length > 0) {
        processor.processMessages([
          { updateComponents: { surfaceId, components: compPatches } },
        ] as never);
      }
    } catch (e) {
      console.error('[ww-workspace] applyPatches error:', e);
    }
  }

  useEffect(() => {
    const actionHandler: ActionListener = (action) => {
      const state = getCurrentThreadState();
      if (!state.skillId) return;

      const key = `${action.name}|${action.surfaceId}|${action.sourceComponentId}`;
      const now = Date.now();
      if (dedupRef.current.key === key && now - dedupRef.current.time < 300) return;
      dedupRef.current = { key, time: now };

      const surface = surfaceMapRef.current.get(action.surfaceId);
      const dmValues: Record<string, unknown> = {};
      if (surface) {
        const root = surface.dataModel.get('/');
        if (root && typeof root === 'object') Object.assign(dmValues, root);
      }

      const resolved: Record<string, unknown> = {};
      if (surface && action.context) {
        for (const [k, v] of Object.entries(action.context)) {
          if (v && typeof v === 'object' && 'path' in (v as Record<string, unknown>)) {
            resolved[k] = surface.dataModel.get((v as { path: string }).path);
          } else {
            resolved[k] = v;
          }
        }
      }

      if (state.isBrowserJs) {
        state.browserRuntime.handleEvent(
          state.skillId,
          action.name,
          { ...dmValues, ...resolved }
        );
      } else {
        kernel.handleEvent(
          state.skillId,
          action.name,
          { ...dmValues, ...resolved }
        );
      }
    };

    const processor = new MessageProcessor([wvCatalog] as never, actionHandler);
    processor.onSurfaceCreated((s) => {
      console.log('[ww-workspace] onSurfaceCreated:', s.id);
      surfaceMapRef.current.set(s.id, s);
      setSurfaceIds((prev) => [...prev, s.id]);
      setLoading(false);
    });
    processor.onSurfaceDeleted((id) => {
      console.log('[ww-workspace] onSurfaceDeleted:', id);
      surfaceMapRef.current.delete(id);
      surfaceThreadMap.current.delete(id);
      setSurfaceIds((prev) => prev.filter((x) => x !== id));
    });
    processorRef.current = processor;

    const onSkillActivated = (e: Event) => {
      const { skillId, threadId, ui, runtime, handlersJs, capabilities } =
        (e as CustomEvent).detail;
      // Quick diagnostic: if ui missing, capture it
      if (!ui || !(ui as Record<string, unknown>).components) {
        setDebugError(
          `skill-activated event: ui missing or no components!\n` +
          `skillId: ${skillId}\n` +
          `ui type: ${typeof ui}\n` +
          `ui keys: ${ui ? Object.keys(ui as object).join(', ') : 'null/undefined'}\n` +
          `full keys: ${Object.keys((e as CustomEvent).detail).join(', ')}`
        );
      }
      if (!threadMatches(threadId)) return;

      if (initializedRef.current === skillId) {
        console.log('[ww-workspace] onSkillActivated SKIP: already initialized with', skillId);
        return;
      }
      initializedRef.current = skillId as string;
      loadSkill(skillId, ui);

      const state = getCurrentThreadState();
      if (runtime === 'browser-js' && handlersJs) {
        state.isBrowserJs = true;
        // Use the current thread key for the callback, not the event's threadId
        const myKey = getThreadKey();
        state.browserRuntime.load(
          skillId,
          handlersJs,
          capabilities || {},
          (sid, p) => applyPatches(sid, myKey === '__global__' ? null : myKey, p)
        );
        requestAnimationFrame(() => {
          state.browserRuntime.handleEvent(skillId, '__init__', {});
        });
      } else {
        state.isBrowserJs = false;
      }
    };
    const onA2uiPatch = (e: Event) => {
      const { skillId, threadId, patches } = (e as CustomEvent).detail;
      applyPatches(skillId, threadId ?? null, patches);
    };
    const onSkillDeactivated = (e: Event) => {
      const { skillId, threadId } = (e as CustomEvent).detail;
      if (!threadMatches(threadId)) return;

      const state = getCurrentThreadState();
      if (skillId === state.skillId) {
        state.isBrowserJs = false;
        state.browserRuntime.unload(skillId);
        state.skillId = null;
        clearSurfaces();
        initializedRef.current = null;
      }
    };

    window.addEventListener('skill-activated', onSkillActivated);
    window.addEventListener('a2ui-patch', onA2uiPatch);
    window.addEventListener('skill-deactivated', onSkillDeactivated);

    return () => {
      initializedRef.current = null;
      window.removeEventListener('skill-activated', onSkillActivated);
      window.removeEventListener('a2ui-patch', onA2uiPatch);
      window.removeEventListener('skill-deactivated', onSkillDeactivated);
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Separate effect: replay initDetail when it arrives (even late, after mount)
  useEffect(() => {
    if (!initDetail || !initDetail.skillId) return;
    if (initializedRef.current && initializedRef.current === initDetail.skillId) return;
    const d = initDetail;
    if (!threadMatches(d.threadId as string | null | undefined)) return;
    if (!d.ui || !(d.ui as Record<string, unknown>).components) {
      setDebugError(
        `initDetail replay: ui missing or no components!\n` +
        `skillId: ${d.skillId}\n` +
        `ui type: ${typeof d.ui}\n` +
        `ui keys: ${d.ui ? Object.keys(d.ui as object).join(', ') : 'null/undefined'}\n` +
        `full keys: ${Object.keys(d).join(', ')}`
      );
      return;
    }
    initializedRef.current = initDetail.skillId as string;
    loadSkill(d.skillId as string, d.ui as Record<string, unknown>);
    const state = getCurrentThreadState();
    if (d.runtime === 'browser-js' && d.handlersJs) {
      state.isBrowserJs = true;
      const myKey = getThreadKey();
      state.browserRuntime.load(
        d.skillId as string,
        d.handlersJs as string,
        (d.capabilities as Record<string, unknown>) || {},
        (sid, p) => applyPatches(sid, myKey === '__global__' ? null : myKey, p),
      );
      requestAnimationFrame(() => {
        state.browserRuntime.handleEvent(d.skillId as string, '__init__', {});
      });
    } else {
      state.isBrowserJs = false;
    }
  }, [initDetail]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="flex flex-col h-full">
      <div className="flex-1 flex flex-col overflow-y-auto py-6">
        {debugError && (
          <div className="m-4 p-3 rounded border border-red-500/30 bg-red-500/10 text-red-600 dark:text-red-400 text-xs font-mono whitespace-pre-wrap">
            {debugError}
          </div>
        )}
        {loading && surfaceIds.length === 0 ? (
          <div className="flex-1 flex items-center justify-center">
            <div className="flex flex-col items-center gap-3">
              <div className="w-6 h-6 border-2 border-primary/30 border-t-primary rounded-full animate-spin" />
              <span className="text-xs text-muted-foreground">正在加载技能界面...</span>
            </div>
          </div>
        ) : (
          <MarkdownContext.Provider value={renderMarkdown}>
            {surfaceIds.map((id) => {
              const surface = surfaceMapRef.current.get(id);
              return surface ? <A2uiSurface key={id} surface={surface as never} /> : null;
            })}
          </MarkdownContext.Provider>
        )}
      </div>
    </div>
  );
}
