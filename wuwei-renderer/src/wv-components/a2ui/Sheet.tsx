import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Sheet, SheetTrigger, SheetContent, SheetHeader, SheetTitle } from '@/wv-components/ui/sheet';

const SheetApi = {
  name: 'Sheet',
  schema: z.object({
    trigger: z.string().describe('The ID of the trigger component.'),
    content: z.string().describe('The ID of the content component.'),
    title: z.string().describe('The title of the sheet.').optional(),
    side: z.enum(['top', 'bottom', 'left', 'right']).default('right').optional().describe('Which side the sheet slides in from.'),
  }).strict(),
};

function SheetComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return (
    <Sheet>
      <SheetTrigger className="cursor-pointer">
        {props.trigger != null ? buildChild(props.trigger as string) : null}
      </SheetTrigger>
      <SheetContent side={(props.side as 'top' | 'bottom' | 'left' | 'right') || 'right'}>
        {(!!props.title || !!props.content) ? (
          <SheetHeader>
            {!!props.title && <SheetTitle>{props.title as string}</SheetTitle>}
          </SheetHeader>
        ) : null}
        {props.content != null ? buildChild(props.content as string) : null}
      </SheetContent>
    </Sheet>
  );
}

export const WvA2uiSheet = createComponentImplementation(SheetApi as never, SheetComponent as never);
