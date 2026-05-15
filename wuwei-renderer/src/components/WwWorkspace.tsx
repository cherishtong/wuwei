import { useRef, useState, useEffect } from 'react';
import { MessageProcessor } from '@a2ui/web_core/v0_9';
import type { SurfaceModel, ActionListener } from '@a2ui/web_core/v0_9';
import { A2uiSurface, MarkdownContext } from '@a2ui/react/v0_9';
import { renderMarkdown } from '@a2ui/markdown-it';
import { wvCatalog } from '@/wv-components/a2ui';
import { Badge } from '@/wv-components/ui/badge';
import { kernel } from '../kernel';

const CATALOG_ID = 'https://a2ui.org/specification/v0_9/basic_catalog.json';

export function WwWorkspace() {
  const [surfaceIds, setSurfaceIds] = useState<string[]>([]);
  const [skillName, setSkillName] = useState('');
  const surfaceMapRef = useRef(new Map<string, SurfaceModel>());
  const currentSkillIdRef = useRef<string | null>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const processorRef = useRef<MessageProcessor<any> | null>(null);
  const dedupRef = useRef({ key: '', time: 0 });

  function loadSkill(skillId: string, skillDisplayName: string, ui: Record<string, unknown>) {
    currentSkillIdRef.current = skillId;
    setSkillName(skillDisplayName || skillId);
    clearSurfaces();

    let components = (ui.components as Record<string, unknown>[]) ?? [];
    const rootIdx = components.findIndex((c) => c['id'] === 'root');
    if (rootIdx > 0) {
      const root = components.splice(rootIdx, 1)[0];
      components = [root, ...components];
    }

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

    const processor = processorRef.current!;
    try {
      processor.processMessages([
        { createSurface: { surfaceId: 'main', catalogId: CATALOG_ID } },
      ] as never);
      processor.processMessages([
        { updateDataModel: { surfaceId: 'main', path: '/', value: dmInit } },
      ] as never);
      if (components.length > 0) {
        processor.processMessages([
          { updateComponents: { surfaceId: 'main', components } },
        ] as never);
      }
    } catch (e) {
      console.error('[ww-workspace] loadSkill error:', e);
    }
  }

  function clearSurfaces() {
    const processor = processorRef.current;
    if (!processor) return;
    for (const id of surfaceMapRef.current.keys()) {
      try {
        processor.processMessages([
          { deleteSurface: { surfaceId: id } },
        ] as never);
      } catch { /* ignore */ }
    }
    surfaceMapRef.current.clear();
    setSurfaceIds([]);
  }

  function applyPatches(skillId: string, patches: Record<string, unknown>[]) {
    if (skillId !== currentSkillIdRef.current || !patches?.length) return;
    const processor = processorRef.current!;

    const dataPatches: { path: string; value: unknown }[] = [];
    const compPatches: Record<string, unknown>[] = [];

    for (const p of patches) {
      if (p['type'] === 'data') {
        dataPatches.push({ path: p['path'] as string, value: p['value'] });
      } else {
        compPatches.push(p);
      }
    }

    try {
      for (const dp of dataPatches) {
        processor.processMessages([
          { updateDataModel: { surfaceId: 'main', path: dp.path, value: dp.value } },
        ] as never);
      }
      if (compPatches.length > 0) {
        processor.processMessages([
          { updateComponents: { surfaceId: 'main', components: compPatches } },
        ] as never);
      }
    } catch (e) {
      console.error('[ww-workspace] applyPatches error:', e);
    }
  }

  useEffect(() => {
    const actionHandler: ActionListener = (action) => {
      if (!currentSkillIdRef.current) return;

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

      kernel.handleEvent(currentSkillIdRef.current, action.name, { ...dmValues, ...resolved });
    };

    const processor = new MessageProcessor([wvCatalog] as never, actionHandler);
    processor.onSurfaceCreated((s) => {
      surfaceMapRef.current.set(s.id, s);
      setSurfaceIds((prev) => [...prev, s.id]);
    });
    processor.onSurfaceDeleted((id) => {
      surfaceMapRef.current.delete(id);
      setSurfaceIds((prev) => prev.filter((x) => x !== id));
    });
    processorRef.current = processor;

    const onSkillActivated = (e: Event) => {
      const { skillId, skillName: name, ui } = (e as CustomEvent).detail;
      loadSkill(skillId, name || skillId, ui);
    };
    const onA2uiPatch = (e: Event) => {
      const { skillId, patches } = (e as CustomEvent).detail;
      applyPatches(skillId, patches);
    };
    const onSkillDeactivated = (e: Event) => {
      const { skillId } = (e as CustomEvent).detail;
      if (skillId === currentSkillIdRef.current) {
        currentSkillIdRef.current = null;
        setSkillName('');
        clearSurfaces();
      }
    };

    window.addEventListener('skill-activated', onSkillActivated);
    window.addEventListener('a2ui-patch', onA2uiPatch);
    window.addEventListener('skill-deactivated', onSkillDeactivated);

    return () => {
      window.removeEventListener('skill-activated', onSkillActivated);
      window.removeEventListener('a2ui-patch', onA2uiPatch);
      window.removeEventListener('skill-deactivated', onSkillDeactivated);
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="flex flex-col h-full">
      {/* Skill header */}
      <div className="flex items-center gap-2 px-4 py-2 border-b bg-muted/30 flex-shrink-0">
        <Badge variant="secondary" className="text-[10px] px-1.5 py-0">
          运行中
        </Badge>
        <span className="text-sm font-medium truncate">{skillName}</span>
        <button
          className="ml-auto p-1 rounded hover:bg-accent transition-colors text-muted-foreground hover:text-foreground"
          onClick={() => {
            if (currentSkillIdRef.current) {
              kernel.deactivateSkill(currentSkillIdRef.current);
            }
          }}
          title="停用 Skill"
        >
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
            <line x1="4" y1="4" x2="12" y2="12" />
            <line x1="12" y1="4" x2="4" y2="12" />
          </svg>
        </button>
      </div>

      {/* A2UI surface */}
      <div className="flex-1 overflow-y-auto p-6">
        <MarkdownContext.Provider value={renderMarkdown}>
          {surfaceIds.map((id) => {
            const surface = surfaceMapRef.current.get(id);
            return surface ? <A2uiSurface key={id} surface={surface as never} /> : null;
          })}
        </MarkdownContext.Provider>
      </div>
    </div>
  );
}
