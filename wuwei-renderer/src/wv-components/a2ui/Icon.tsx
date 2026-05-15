import { createComponentImplementation } from '@a2ui/react/v0_9';
import { IconApi } from '@a2ui/web_core/v0_9/basic_catalog';

const NAME_OVERRIDES: Record<string, string> = {
  play: 'play_arrow', rewind: 'fast_rewind',
  favoriteOff: 'favorite_border', starOff: 'star_border',
};

function toMaterialSymbol(str: string) {
  return NAME_OVERRIDES[str] ?? str.replace(/[A-Z]/g, (l) => '_' + l.toLowerCase());
}

function IconComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const isPath = typeof props.name === 'object' && props.name !== null && 'svgPath' in (props.name as object);
  const baseStyle: React.CSSProperties = {
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    fontSize: 'var(--a2ui-icon-size, var(--a2ui-font-size-xl, 24px))',
    color: 'var(--a2ui-icon-color, inherit)', lineHeight: 1,
  };

  if (isPath) {
    return (
      <svg viewBox="0 0 24 24" style={{ ...baseStyle, fill: 'currentColor', width: 'var(--a2ui-icon-size, 24px)', height: 'var(--a2ui-icon-size, 24px)' }}>
        <path d={(props.name as { svgPath: string }).svgPath} />
      </svg>
    );
  }

  const iconName = typeof props.name === 'string' ? toMaterialSymbol(props.name) : '';
  return (
    <span className="material-symbols-outlined" style={{
      ...baseStyle,
      fontFamily: 'var(--a2ui-icon-font-family, "Material Symbols Outlined", sans-serif)',
      fontVariationSettings: 'var(--a2ui-icon-font-variation-settings, "FILL" 1)',
      fontWeight: 'normal', fontStyle: 'normal',
      letterSpacing: 'normal', textTransform: 'none',
    }}>{iconName}</span>
  );
}

export const WvA2uiIcon = createComponentImplementation(IconApi as never, IconComponent as never);
