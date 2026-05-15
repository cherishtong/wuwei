import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Collapsible, CollapsibleTrigger, CollapsibleContent } from '@/wv-components/ui/collapsible';

const CollapsibleApi = {
  name: 'Collapsible',
  schema: z.object({
    trigger: z.string().describe('The ID of the trigger component.'),
    content: z.string().describe('The ID of the content component.'),
  }).strict(),
};

function CollapsibleComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return (
    <Collapsible>
      <CollapsibleTrigger className="cursor-pointer">
        {props.trigger != null ? buildChild(props.trigger as string) : null}
      </CollapsibleTrigger>
      <CollapsibleContent>
        {props.content != null ? buildChild(props.content as string) : null}
      </CollapsibleContent>
    </Collapsible>
  );
}

export const WvA2uiCollapsible = createComponentImplementation(CollapsibleApi as never, CollapsibleComponent as never);
