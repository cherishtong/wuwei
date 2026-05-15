import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Popover, PopoverTrigger, PopoverContent } from '@/wv-components/ui/popover';

const PopoverApi = {
  name: 'Popover',
  schema: z.object({
    trigger: z.string().describe('The ID of the trigger component.'),
    content: z.string().describe('The ID of the content component.'),
  }).strict(),
};

function PopoverComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return (
    <Popover>
      <PopoverTrigger className="cursor-pointer">
        {props.trigger != null ? buildChild(props.trigger as string) : null}
      </PopoverTrigger>
      <PopoverContent>
        {props.content != null ? buildChild(props.content as string) : null}
      </PopoverContent>
    </Popover>
  );
}

export const WvA2uiPopover = createComponentImplementation(PopoverApi as never, PopoverComponent as never);
