import { useState, useEffect } from 'react';
import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Pagination as ShadcnPagination, PaginationContent, PaginationItem, PaginationLink, PaginationPrevious, PaginationNext, PaginationEllipsis } from '@/wv-components/ui/pagination';

const PaginationApi = {
  name: 'Pagination',
  schema: z.object({
    currentPage: z.number().default(1).describe('The current page number.'),
    totalPages: z.number().describe('The total number of pages.'),
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

function PaginationComponent({ props }: { props: Record<string, unknown> & { setValue?: (val: number) => void }; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const total = (props.totalPages as number) || 1;
  const [page, setPage] = useState((props.currentPage as number) || 1);

  useEffect(() => { setPage((props.currentPage as number) || 1); }, [props.currentPage]);

  const goTo = (p: number) => {
    if (p < 1 || p > total) return;
    setPage(p);
    props.setValue?.(p);
  };

  if (total <= 1) return null;

  return (
    <ShadcnPagination>
      <PaginationContent>
        <PaginationItem>
          <PaginationPrevious onClick={() => goTo(page - 1)} />
        </PaginationItem>
        {getPageNumbers(page, total).map((p, i) => (
          <PaginationItem key={i}>
            {p === 'ellipsis' ? (
              <PaginationEllipsis />
            ) : (
              <PaginationLink isActive={p === page} onClick={() => goTo(p)}>
                {p}
              </PaginationLink>
            )}
          </PaginationItem>
        ))}
        <PaginationItem>
          <PaginationNext onClick={() => goTo(page + 1)} />
        </PaginationItem>
      </PaginationContent>
    </ShadcnPagination>
  );
}

export const WvA2uiPagination = createComponentImplementation(PaginationApi as never, PaginationComponent as never);
