import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { ScrollArea as ShadcnScrollArea } from '@/wv-components/ui/scroll-area';

const ScrollAreaApi = {
  name: 'ScrollArea',
  schema: z.object({
    child: z.string().describe('The ID of the child component.'),
    height: z.number().optional().describe('Maximum height in pixels.'),
  }).strict(),
};

function ScrollAreaComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const height = props.height as number | undefined;

  return (
    <ShadcnScrollArea style={height ? { height: `${height}px` } : undefined} className="w-full">
      {props.child != null ? buildChild(props.child as string) : null}
    </ShadcnScrollArea>
  );
}

export const WvA2uiScrollArea = createComponentImplementation(ScrollAreaApi as never, ScrollAreaComponent as never);
