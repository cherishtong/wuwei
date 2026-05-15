import { createComponentImplementation } from '@a2ui/react/v0_9';
import { z } from 'zod';
import { Progress as ShadcnProgress } from '@/wv-components/ui/progress';

export const ProgressApi = {
  name: 'Progress',
  schema: z.object({
    value: z.number().min(0).max(100).default(0).describe('Progress value 0-100.'),
    label: z.string().optional().describe('Accessible label for the progress bar.'),
  }).strict(),
};

function ProgressComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string) => React.ReactNode; context: unknown }) {
  const value = typeof props.value === 'number' ? props.value : Number(props.value ?? 0);
  return <ShadcnProgress value={value} className="w-full" />;
}

export const WvA2uiProgress = createComponentImplementation(ProgressApi as never, ProgressComponent as never);
