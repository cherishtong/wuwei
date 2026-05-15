import { createComponentImplementation } from '@a2ui/react/v0_9';
import { z } from 'zod';
import { Badge as ShadcnBadge } from '@/wv-components/ui/badge';

const DynamicStringSchema = z.union([
  z.string(),
  z.object({ path: z.string() }),
]);

export const BadgeApi = {
  name: 'Badge',
  schema: z.object({
    label: DynamicStringSchema.describe('The text displayed inside the badge.'),
    variant: z
      .enum(['default', 'secondary', 'destructive', 'outline'])
      .default('default')
      .optional()
      .describe('Badge style variant.'),
  }).strict(),
};

function BadgeComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string) => React.ReactNode; context: unknown }) {
  const label = typeof props.label === 'string' ? props.label : String(props.label ?? '');
  const variant = (props.variant as string) || 'default';
  return <ShadcnBadge variant={variant as 'default' | 'secondary' | 'destructive' | 'outline'}>{label}</ShadcnBadge>;
}

export const WvA2uiBadge = createComponentImplementation(BadgeApi as never, BadgeComponent as never);
