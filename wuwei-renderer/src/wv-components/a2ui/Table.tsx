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
 * Each cell value is rendered as Markdown (via useMarkdownRenderer), so
 * bold, italic, links, and inline HTML all work inside cells.
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

function CellContent({ text }: { text: string }) {
  const renderer = useMarkdownRenderer();
  const [html, setHtml] = useState<string | null>(null);

  useEffect(() => {
    if (renderer) {
      renderer(text, undefined).then(setHtml).catch(() => setHtml(null));
    }
  }, [text, renderer]);

  if (html) {
    return <span dangerouslySetInnerHTML={{ __html: html }} />;
  }
  return <>{text}</>;
}

function TableComponent({
  props,
}: {
  props: Record<string, unknown>;
  buildChild: (id: string, basePath?: string) => React.ReactNode;
  context: unknown;
}) {
  const columns = Array.isArray(props.columns) ? props.columns : [];
  const rows = Array.isArray(props.rows) ? props.rows : [];
  const captionRaw = props.caption;

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
                    <CellContent text={resolveString(cell)} />
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
