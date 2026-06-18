import { createComponentImplementation } from '@a2ui/react/v0_9';
import { z } from 'zod';
import React, { Suspense, lazy, useMemo, useState, useEffect } from 'react';
import { useTheme } from '@/contexts/ThemeContext';

export const DocxEditorApi = {
  name: 'DocxEditor',
  schema: z.object({
    documentBuffer: z.string().optional(),
    mode: z.enum(['editing', 'preview']).optional().default('editing'),
    height: z.union([z.number(), z.string()]).optional(),
    weight: z.number().optional(),
  }).strict(),
};

// Lazy-load Chinese locale + editor
let zhCN: unknown;
import('@eigenpal/docx-editor-i18n/zh-CN').then(m => { zhCN = m.default || m; }).catch(() => {});

// Lazy-load the editor (heavy dependency)
const EigenpalEditor = lazy(() =>
  import('@eigenpal/docx-editor-react').then(m => ({
    default: m.DocxEditor as React.ComponentType<Record<string, unknown>>,
  }))
);

function DocxEditorComponent({ props }: {
  props: Record<string, unknown>;
  buildChild: (id: string, basePath?: string) => React.ReactNode;
  context: unknown;
}) {
  const height = props.height || 500;
  const heightStyle = typeof height === 'number' ? `${height}px` : String(height);
  const weight = props.weight as number | undefined;
  const mode = (props.mode as string) || 'editing';
  const docBufferB64 = props.documentBuffer as string | undefined;

  // Lazy-init empty document
  const { resolved: themeMode } = useTheme();
  const [emptyDoc, setEmptyDoc] = useState<unknown>(undefined);

  useEffect(() => {
    import('@eigenpal/docx-editor-core').then(core => {
      if (core.createEmptyDocument) setEmptyDoc(core.createEmptyDocument());
    }).catch(() => {});
  }, []);

  // Parse base64 document buffer if provided
  const docBuffer = useMemo((): ArrayBuffer | undefined => {
    if (!docBufferB64) return undefined;
    try {
      const binary = atob(docBufferB64);
      const bytes = new Uint8Array(binary.length);
      for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
      return bytes.buffer;
    } catch { return undefined; }
  }, [docBufferB64]);

  const editorMode = mode === 'preview' ? 'viewing' as const : 'editing' as const;
  const isDark = themeMode === 'dark';

  return (
    <div className={isDark ? 'dark' : ''} style={{
      width: '100%',
      height: heightStyle,
      minHeight: '300px',
      border: '1px solid hsl(var(--border))',
      borderRadius: '0.5rem',
      overflow: 'hidden',
      backgroundColor: isDark ? 'hsl(222.2 84% 4.9%)' : '#fff',
      flex: weight ? `${weight} ${weight} 0%` : undefined,
    }}>
      <Suspense fallback={
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          height: '100%', color: '#888', fontSize: '0.875rem',
        }}>
          <div style={{ textAlign: 'center' }}>
            <div style={{ fontSize: '2rem', marginBottom: '0.5rem' }}>📄</div>
            Loading DocxEditor...
          </div>
        </div>
      }>
        <EigenpalEditor
          document={emptyDoc}
          documentBuffer={docBuffer ?? undefined}
          mode={editorMode}
          showToolbar={true}
          i18n={zhCN as any}
          style={{ height: '100%', width: '100%' }}
          renderTitleBarRight={() =>
            React.createElement('button', {
              onClick: () => window.dispatchEvent(new CustomEvent('wuwei-sheet-close', { detail: { id: 'edit-sheet' } })),
              className: 'ep-close-btn',
              title: '关闭',
              style: {
                background: 'none', border: 'none', cursor: 'pointer',
                fontSize: '1.25rem', padding: '0 0.5rem', color: 'inherit',
              },
            }, '✕')
          }
        />
      </Suspense>
    </div>
  );
}

export const WvA2uiDocxEditor = createComponentImplementation(
  DocxEditorApi as never,
  DocxEditorComponent as never,
);
