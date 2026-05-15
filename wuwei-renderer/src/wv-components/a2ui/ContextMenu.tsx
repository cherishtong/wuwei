import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { ContextMenu as ShadcnContextMenu, ContextMenuTrigger, ContextMenuContent, ContextMenuItem } from '@/wv-components/ui/context-menu';

const ContextMenuApi = {
  name: 'ContextMenu',
  schema: z.object({
    trigger: z.string().describe('The ID of the trigger component.'),
    items: z.array(z.object({
      label: z.string(),
      value: z.string(),
    })).describe('The menu items.'),
  }).strict(),
};

function ContextMenuComponent({ props, buildChild }: { props: Record<string, unknown> & { setValue?: (val: string) => void }; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const items = (Array.isArray(props.items) ? props.items : []) as Array<{ label: string; value: string }>;

  return (
    <ShadcnContextMenu>
      <ContextMenuTrigger className="cursor-pointer">
        {props.trigger != null ? buildChild(props.trigger as string) : null}
      </ContextMenuTrigger>
      <ContextMenuContent>
        {items.map((item) => (
          <ContextMenuItem
            key={item.value}
            onClick={() => props.setValue?.(item.value)}
          >
            {item.label}
          </ContextMenuItem>
        ))}
      </ContextMenuContent>
    </ShadcnContextMenu>
  );
}

export const WvA2uiContextMenu = createComponentImplementation(ContextMenuApi as never, ContextMenuComponent as never);
