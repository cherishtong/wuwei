import { createComponentImplementation } from '@a2ui/react/v0_9';
import { ColumnApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Fragment } from 'react';

function getWeightStyle(weight: unknown): Record<string, unknown> {
  if (typeof weight !== 'number') return {};
  return { flex: `${weight}`, minWidth: 0, minHeight: 0 };
}

function ColumnComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const children = Array.isArray(props.children) ? props.children : [];
  const justify = mapJustify(props.justify as string);
  const align = mapAlign(props.align as string);
  const weightStyle = getWeightStyle(props.weight);

  return (
    <div className="flex flex-col box-border gap-4 min-h-full w-full" style={{ justifyContent: justify, alignItems: align, ...weightStyle }}>
      {children.map((child: string | { id: string; basePath?: string }, i: number) => {
        if (typeof child === 'string') return <Fragment key={`${child}-${i}`}>{buildChild(child)}</Fragment>;
        if (child && typeof child === 'object' && 'id' in child) return <Fragment key={`${child.id}-${i}`}>{buildChild(child.id, child.basePath)}</Fragment>;
        return null;
      })}
    </div>
  );
}

function mapJustify(j?: string) {
  switch (j) {
    case 'center': return 'center'; case 'end': return 'flex-end';
    case 'spaceAround': return 'space-around'; case 'spaceBetween': return 'space-between';
    case 'spaceEvenly': return 'space-evenly'; case 'start': return 'flex-start';
    case 'stretch': return 'stretch'; default: return 'flex-start';
  }
}
function mapAlign(a?: string) {
  switch (a) {
    case 'start': return 'flex-start'; case 'center': return 'center';
    case 'end': return 'flex-end'; case 'stretch': return 'stretch';
    default: return 'stretch';
  }
}

export const WvA2uiColumn = createComponentImplementation(ColumnApi as never, ColumnComponent as never);
