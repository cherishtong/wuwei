import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { HoverCard, HoverCardTrigger, HoverCardContent } from '@/wv-components/ui/hover-card';

const HoverCardApi = {
  name: 'HoverCard',
  schema: z.object({
    trigger: z.string().describe('The ID of the trigger component.'),
    content: z.string().describe('The ID of the content component.'),
  }).strict(),
};

function HoverCardComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return (
    <HoverCard>
      <HoverCardTrigger className="cursor-pointer">
        {props.trigger != null ? buildChild(props.trigger as string) : null}
      </HoverCardTrigger>
      <HoverCardContent>
        {props.content != null ? buildChild(props.content as string) : null}
      </HoverCardContent>
    </HoverCard>
  );
}

export const WvA2uiHoverCard = createComponentImplementation(HoverCardApi as never, HoverCardComponent as never);
