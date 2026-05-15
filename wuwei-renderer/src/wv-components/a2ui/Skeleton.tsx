import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Skeleton as ShadcnSkeleton } from '@/wv-components/ui/skeleton';

const SkeletonApi = {
  name: 'Skeleton',
  schema: z.object({
    width: z.string().optional().describe('CSS width value.'),
    height: z.string().default('1rem').optional().describe('CSS height value.'),
  }).strict(),
};

function SkeletonComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return (
    <ShadcnSkeleton
      style={{
        width: (props.width as string) || '100%',
        height: (props.height as string) || '1rem',
      }}
    />
  );
}

export const WvA2uiSkeleton = createComponentImplementation(SkeletonApi as never, SkeletonComponent as never);
