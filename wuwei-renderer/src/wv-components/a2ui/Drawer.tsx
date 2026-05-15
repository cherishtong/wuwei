import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Drawer, DrawerTrigger, DrawerContent, DrawerHeader, DrawerTitle } from '@/wv-components/ui/drawer';

const DrawerApi = {
  name: 'Drawer',
  schema: z.object({
    trigger: z.string().describe('The ID of the trigger component.'),
    content: z.string().describe('The ID of the content component.'),
    title: z.string().describe('The title of the drawer.').optional(),
  }).strict(),
};

function DrawerComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return (
    <Drawer>
      <DrawerTrigger className="cursor-pointer">
        {props.trigger != null ? buildChild(props.trigger as string) : null}
      </DrawerTrigger>
      <DrawerContent>
        {(!!props.title || !!props.content) ? (
          <DrawerHeader>
            {!!props.title && <DrawerTitle>{props.title as string}</DrawerTitle>}
          </DrawerHeader>
        ) : null}
        {props.content != null ? buildChild(props.content as string) : null}
      </DrawerContent>
    </Drawer>
  );
}

export const WvA2uiDrawer = createComponentImplementation(DrawerApi as never, DrawerComponent as never);
