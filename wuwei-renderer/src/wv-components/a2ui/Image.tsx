import { createComponentImplementation } from '@a2ui/react/v0_9';
import { ImageApi } from '@a2ui/web_core/v0_9/basic_catalog';

function ImageComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const fit = props.fit === 'scaleDown' ? 'scale-down' : (props.fit as string) || 'fill';
  const style: React.CSSProperties = {
    boxSizing: 'border-box',
    objectFit: fit as React.CSSProperties['objectFit'],
    display: 'block',
    borderRadius: 'var(--a2ui-image-border-radius, 0)',
    maxWidth: '100%',
    height: 'auto',
    minHeight: 0,
  };

  if (props.variant === 'icon') {
    style.width = 'var(--a2ui-image-icon-size, 24px)';
    style.height = 'var(--a2ui-image-icon-size, 24px)';
  } else if (props.variant === 'avatar') {
    style.width = 'var(--a2ui-image-avatar-size, 40px)';
    style.height = 'var(--a2ui-image-avatar-size, 40px)';
    style.borderRadius = '50%';
  } else if (props.variant === 'smallFeature') {
    style.maxWidth = 'var(--a2ui-image-small-feature-size, 100px)';
  } else if (props.variant === 'largeFeature') {
    style.maxHeight = 'var(--a2ui-image-large-feature-size, 400px)';
  } else if (props.variant === 'header') {
    style.height = 'var(--a2ui-image-header-size, 200px)';
    style.objectFit = 'cover';
  }

  return <img src={props.url as string} alt={(props.description as string) || ''} style={style} />;
}

export const WvA2uiImage = createComponentImplementation(ImageApi as never, ImageComponent as never);
