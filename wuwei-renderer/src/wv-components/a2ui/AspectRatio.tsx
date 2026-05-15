import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { AspectRatio as ShadcnAspectRatio } from '@/wv-components/ui/aspect-ratio';

const AspectRatioApi = {
  name: 'AspectRatio',
  schema: z.object({
    child: z.string().describe('The ID of the child component.'),
    ratio: z.number().default(1).describe('The aspect ratio (width / height).').optional(),
  }).strict(),
};

function AspectRatioComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return (
    <ShadcnAspectRatio ratio={(props.ratio as number) || 1}>
      {props.child != null ? buildChild(props.child as string) : null}
    </ShadcnAspectRatio>
  );
}

export const WvA2uiAspectRatio = createComponentImplementation(AspectRatioApi as never, AspectRatioComponent as never);
