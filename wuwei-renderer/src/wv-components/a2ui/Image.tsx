import { createComponentImplementation } from '@a2ui/react/v0_9';
import { ImageApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { cn } from '@/wv-components/ui/lib/utils';

function ImageComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const fit = props.fit === 'scaleDown' ? 'scale-down' : (props.fit as string) || 'fill';
  const variant = props.variant as string | undefined;

  return (
    <img
      src={props.url as string}
      alt={(props.description as string) || ''}
      className={cn(
        'block max-w-full h-auto min-h-0 rounded-none',
        variant === 'icon' && 'w-6 h-6',
        variant === 'avatar' && 'w-10 h-10 rounded-full',
        variant === 'smallFeature' && 'max-w-[100px]',
        variant === 'largeFeature' && 'max-h-[400px]',
        variant === 'header' && 'h-[200px] object-cover',
      )}
      style={{ objectFit: fit as React.CSSProperties['objectFit'] }}
    />
  );
}

export const WvA2uiImage = createComponentImplementation(ImageApi as never, ImageComponent as never);
