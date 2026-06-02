import { useState, useEffect, useCallback } from 'react';
import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { PaginationContent, PaginationItem, PaginationLink, PaginationPrevious, PaginationNext, PaginationEllipsis } from '@/wv-components/ui/pagination';

const PaginationApi = {
  name: 'Pagination',
  schema: z.object({
    currentPage: z.union([z.number(), z.object({ path: z.string() })]).default(1).describe('Current page number or DataModel path binding.'),
    totalPages: z.number().describe('The total number of pages.'),
    totalItems: z.number().optional().describe('Total number of items (for display).'),
    action: z.object({
      event: z.object({
        name: z.string().describe('The event name dispatched to the skill handler when page changes.'),
        context: z.record(z.object({ path: z.string() })).optional().describe('Additional context values from DataModel.'),
      }).strict(),
    }).optional().describe('Action dispatched when page changes.'),
  }).strict(),
};

function getPageNumbers(current: number, total: number): (number | 'ellipsis')[] {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
  const pages: (number | 'ellipsis')[] = [];
  if (current <= 3) {
    pages.push(1, 2, 3, 4, 'ellipsis', total);
  } else if (current >= total - 2) {
    pages.push(1, 'ellipsis', total - 3, total - 2, total - 1, total);
  } else {
    pages.push(1, 'ellipsis', current - 1, current, current + 1, 'ellipsis', total);
  }
  return pages;
}

/** Resolve a prop that might be a DataModel binding {path:"/x"} or a literal value. */
function resolveBinding(value: unknown, dataContext: any, fallback: number): number {
  if (typeof value === 'number') return value;
  if (value && typeof value === 'object' && 'path' in (value as any)) {
    return dataContext?.get((value as any).path) ?? fallback;
  }
  return fallback;
}

function PaginationComponent({ props, context }: { props: Record<string, unknown> & { setValue?: (val: number) => void }; buildChild: (id: string, basePath?: string) => React.ReactNode; context: any }) {
  const total = (props.totalPages as number) || 1;
  const totalItems = typeof props.totalItems === 'number' ? props.totalItems : undefined;
  const resolved = resolveBinding(props.currentPage, context?.dataContext, 1);
  const [page, setPage] = useState(resolved);

  useEffect(() => { setPage(resolveBinding(props.currentPage, context?.dataContext, 1)); }, [props.currentPage]);

  const goTo = useCallback((p: number) => {
    if (p < 1 || p > total) return;
    setPage(p);
    props.setValue?.(p);
    const a = (props as any).action;
    if (a?.event?.name && context?.dispatchAction) {
      context.dispatchAction({
        event: {
          name: a.event.name,
          context: { page: p },
        }
      });
    }
  }, [total, props, context]);

  if (total <= 1 && totalItems == null) return null;

  const infoText = totalItems != null
    ? `共 ${totalItems} 条 / ${total} 页`
    : `共 ${total} 页`;

  return (
    <div className="flex items-center gap-2">
      <span className="text-sm text-muted-foreground whitespace-nowrap shrink-0">{infoText}</span>
      <nav role="navigation" aria-label="pagination" className="flex flex-1 justify-end">
        <ul className="flex flex-row items-center gap-1">
          <li>
            <PaginationPrevious onClick={() => goTo(page - 1)} />
          </li>
          {getPageNumbers(page, total).map((p, i) => (
            <li key={i}>
              {p === 'ellipsis' ? (
                <PaginationEllipsis />
              ) : (
                <PaginationLink isActive={p === page} onClick={() => goTo(p)}>
                  {p}
                </PaginationLink>
              )}
            </li>
          ))}
          <li>
            <PaginationNext onClick={() => goTo(page + 1)} />
          </li>
        </ul>
      </nav>
    </div>
  );
}

export const WvA2uiPagination = createComponentImplementation(PaginationApi as never, PaginationComponent as never);
