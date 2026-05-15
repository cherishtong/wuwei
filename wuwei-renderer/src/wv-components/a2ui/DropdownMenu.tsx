import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { DropdownMenu as ShadcnDropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from '@/wv-components/ui/dropdown-menu';

const DropdownMenuApi = {
  name: 'DropdownMenu',
  schema: z.object({
    trigger: z.string().describe('The ID of the trigger component.'),
    items: z.array(z.object({
      label: z.string(),
      value: z.string(),
    })).describe('The menu items.'),
  }).strict(),
};

function DropdownMenuComponent({ props, buildChild }: { props: Record<string, unknown> & { setValue?: (val: string) => void }; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const items = (Array.isArray(props.items) ? props.items : []) as Array<{ label: string; value: string }>;

  return (
    <ShadcnDropdownMenu>
      <DropdownMenuTrigger className="cursor-pointer">
        {props.trigger != null ? buildChild(props.trigger as string) : null}
      </DropdownMenuTrigger>
      <DropdownMenuContent>
        {items.map((item) => (
          <DropdownMenuItem
            key={item.value}
            onClick={() => props.setValue?.(item.value)}
          >
            {item.label}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </ShadcnDropdownMenu>
  );
}

export const WvA2uiDropdownMenu = createComponentImplementation(DropdownMenuApi as never, DropdownMenuComponent as never);
