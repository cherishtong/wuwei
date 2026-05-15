import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Breadcrumb as ShadcnBreadcrumb, BreadcrumbList, BreadcrumbItem, BreadcrumbLink, BreadcrumbPage, BreadcrumbSeparator } from '@/wv-components/ui/breadcrumb';

const BreadcrumbApi = {
  name: 'Breadcrumb',
  schema: z.object({
    items: z.array(z.object({
      label: z.string(),
      href: z.string().optional(),
    })).describe('The breadcrumb items. The last item is treated as the current page.'),
  }).strict(),
};

function BreadcrumbComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const items = (Array.isArray(props.items) ? props.items : []) as Array<{ label: string; href?: string }>;

  if (!items.length) return null;

  return (
    <ShadcnBreadcrumb>
      <BreadcrumbList>
        {items.map((item, i) => {
          const isLast = i === items.length - 1;
          return (
            <BreadcrumbItem key={i}>
              {isLast ? (
                <BreadcrumbPage>{item.label}</BreadcrumbPage>
              ) : (
                <>
                  <BreadcrumbLink href={item.href || '#'}>{item.label}</BreadcrumbLink>
                  <BreadcrumbSeparator />
                </>
              )}
            </BreadcrumbItem>
          );
        })}
      </BreadcrumbList>
    </ShadcnBreadcrumb>
  );
}

export const WvA2uiBreadcrumb = createComponentImplementation(BreadcrumbApi as never, BreadcrumbComponent as never);
