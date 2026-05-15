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

  if (isPath) {
    return (
      <svg viewBox="0 0 24 24" className="inline-flex items-center justify-center w-6 h-6 fill-current" style={{ lineHeight: 1 }}>
        <path d={(props.name as { svgPath: string }).svgPath} />
      </svg>
    );
  }

  const iconName = typeof props.name === 'string' ? toMaterialSymbol(props.name) : '';
  return (
    <span className="material-symbols-outlined inline-flex items-center justify-center text-2xl leading-none" style={{ fontVariationSettings: '"FILL" 1' }}>
      {iconName}
    </span>
  );
}

export const WvA2uiIcon = createComponentImplementation(IconApi as never, IconComponent as never);
