import { createComponentImplementation } from '@a2ui/react/v0_9';
import { z } from 'zod';
import {
  Table as ShadcnTable,
  TableHeader,
  TableBody,
  TableHead,
  TableRow,
  TableCell,
  TableCaption,
} from '@/wv-components/ui/table';
import { useMarkdownRenderer } from '@a2ui/react/v0_9';
import { useState, useEffect } from 'react';
import { Button } from '@/wv-components/ui/button';
import { Eye, EyeOff } from 'lucide-react';
import { kernel } from '@/kernel';

/**
 * A2UI Table component — uses shadcn/ui Table for proper HTML <table> rendering.
 *
 * JSON shape:
 * {
 *   "component": "Table",
 *   "id": "...",
 *   "columns": [{ "title": "Name", "key": "name" }, ...],
 *   "rows": [["Alice", "28"], ["Bob", "32"], ...],
 *   "caption": "..." // optional
 * }
 *
 * Cell formats:
 *   [btn:label:eventName:id] — shadcn Button
 *   [eye:eventName:id]      — Eye icon button (password shown)
 *   [eye-off:eventName:id]  — EyeOff icon button (password hidden)
 *   Remaining text after icons is displayed as-is (no markdown).
 */

const DynamicStringSchema = z.union([
  z.string(),
  z.object({ path: z.string() }),
  z.object({ call: z.string(), args: z.record(z.any()).optional(), returnType: z.string().optional() }),
]);

export const TableApi = {
  name: 'Table',
  schema: z
    .object({
      columns: z
        .array(
          z.object({
            title: DynamicStringSchema.describe('Column header text (supports Markdown).'),
            key: z.string().describe('Stable key for this column.'),
          })
        )
        .min(1)
        .describe('Column definitions.'),
      rows: z
        .array(z.array(DynamicStringSchema))
        .describe(
          'Row data. Each row is an array of cell values (one per column). Values are rendered as Markdown.'
        ),
      caption: DynamicStringSchema.optional().describe('Optional table caption displayed below the table.'),
    })
    .strict(),
};

function resolveString(value: unknown): string {
  if (typeof value === 'string') return value;
  if (value && typeof value === 'object' && 'path' in value) {
    return String((value as { path: string }).path);
  }
  return String(value ?? '');
}

function CellContent({ text, onAction }: { text: string; onAction?: (name: string, id: string) => void }) {
  // Always call hooks at top level (React rules)
  const renderer = useMarkdownRenderer();
  const [html, setHtml] = useState<string | null>(null);

  // Parse [eye:eventName:id] / [eye-off:eventName:id] — eye toggle icon
  const eyeBtnRegex = /\[(eye|eye-off):([^:]+):([^\]]+)\]/g;
  const eyeBtns: { variant: 'eye' | 'eye-off'; name: string; id: string }[] = [];
  let cleanText = text;
  let em;
  while ((em = eyeBtnRegex.exec(text)) !== null) {
    eyeBtns.push({ variant: em[1] as 'eye' | 'eye-off', name: em[2], id: em[3] });
    cleanText = cleanText.replace(em[0], '');
  }

  // Parse [btn:label:eventName:id] — shadcn Button
  const btnRegex = /\[btn:([^:]+):([^:]+):([^\]]+)\]/g;
  const buttons: { label: string; name: string; id: string }[] = [];
  let mb;
  while ((mb = btnRegex.exec(text)) !== null) {
    buttons.push({ label: mb[1], name: mb[2], id: mb[3] });
    cleanText = cleanText.replace(mb[0], '');
  }

  // Only run markdown when there are no controls (safe for passwords, etc.)
  const hasControls = eyeBtns.length > 0 || buttons.length > 0;
  useEffect(() => {
    if (renderer && !hasControls) {
      renderer(text, undefined).then(setHtml).catch(() => setHtml(null));
    } else {
      setHtml(null);
    }
  }, [text, renderer, hasControls]);

  if (hasControls && onAction) {
    return (
      <span className="inline-flex items-center gap-1">
        {cleanText ? <span>{cleanText}</span> : null}
        {eyeBtns.map((b, i) => (
          <Button key={`eye-${i}`} size="sm" variant="ghost"
            className="h-6 w-6 p-0"
            onClick={(e) => { e.stopPropagation(); onAction(b.name, b.id); }}>
            {b.variant === 'eye-off' ? <EyeOff size={14} /> : <Eye size={14} />}
          </Button>
        ))}
        {buttons.map((b, i) => {
          const isDel = b.name.includes('del');
          return (
            <Button key={`btn-${i}`} size="sm" variant={isDel ? "destructive" : "outline"}
              className="text-xs h-6 px-2"
              onClick={(e) => { e.stopPropagation(); onAction(b.name, b.id); }}>
              {b.label}
            </Button>
          );
        })}
      </span>
    );
  }
  if (html) return <span dangerouslySetInnerHTML={{ __html: html }} />;
  return <>{text}</>;
}

function TableComponent({
  props, context,
}: {
  props: Record<string, unknown>;
  buildChild: (id: string, basePath?: string) => React.ReactNode;
  context: { dispatchAction?: (action: unknown) => Promise<void>; componentModel?: { id: string } };
}) {
  const columns = Array.isArray(props.columns) ? props.columns : [];
  const rows = Array.isArray(props.rows) ? props.rows : [];
  const captionRaw = props.caption;

  const handleAction = (name: string, id: string) => {
    if (context?.dispatchAction) {
      context.dispatchAction({ event: { name, context: { id } } });
    } else {
      // Fallback: dispatch directly to kernel
      const skillId = (window as any).__wuwei_activeSkillId;
      if (skillId) kernel.handleEvent(skillId, name, { id });
    }
  };

  return (
    <ShadcnTable>
      {captionRaw != null ? (
        <TableCaption>
          <CellContent text={resolveString(captionRaw)} />
        </TableCaption>
      ) : null}
      <TableHeader>
        <TableRow>
          {columns.map((col, i) => {
            const colObj = col as { title: unknown; key: string };
            return (
              <TableHead key={colObj.key ?? i}>
                <CellContent text={resolveString(colObj.title)} />
              </TableHead>
            );
          })}
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.length === 0 ? (
          <TableRow>
            <TableCell colSpan={columns.length} className="text-center text-muted-foreground">
              No data
            </TableCell>
          </TableRow>
        ) : (
          rows.map((row, ri) => (
            <TableRow key={ri}>
              {Array.isArray(row) &&
                row.map((cell, ci) => (
                  <TableCell key={ci}>
                    <CellContent text={resolveString(cell)} onAction={handleAction} />
                  </TableCell>
                ))}
            </TableRow>
          ))
        )}
      </TableBody>
    </ShadcnTable>
  );
}

export const WvA2uiTable = createComponentImplementation(TableApi as never, TableComponent as never);
