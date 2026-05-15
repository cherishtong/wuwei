import { createComponentImplementation } from '@a2ui/react/v0_9';
import { z } from 'zod';
import { Avatar as ShadcnAvatar, AvatarImage, AvatarFallback } from '@/wv-components/ui/avatar';

const DynamicStringSchema = z.union([
  z.string(),
  z.object({ path: z.string() }),
]);

export const AvatarApi = {
  name: 'Avatar',
  schema: z.object({
    src: DynamicStringSchema.optional().describe('Image URL for the avatar.'),
    alt: z.string().optional().describe('Alt text for the avatar image.'),
    fallback: DynamicStringSchema.default('?').describe('Text shown when image fails to load.'),
  }).strict(),
};

function AvatarComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string) => React.ReactNode; context: unknown }) {
  const src = typeof props.src === 'string' ? props.src : undefined;
  const alt = typeof props.alt === 'string' ? props.alt : '';
  const fallback = typeof props.fallback === 'string' ? props.fallback : '?';
  return (
    <ShadcnAvatar>
      {src ? <AvatarImage src={src} alt={alt} /> : null}
      <AvatarFallback>{fallback}</AvatarFallback>
    </ShadcnAvatar>
  );
}

export const WvA2uiAvatar = createComponentImplementation(AvatarApi as never, AvatarComponent as never);
