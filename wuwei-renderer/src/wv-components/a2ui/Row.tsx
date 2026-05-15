import { createComponentImplementation } from '@a2ui/react/v0_9';
import { RowApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Fragment } from 'react';

function RowComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const children = Array.isArray(props.children) ? props.children : [];
  const justify = mapJustify(props.justify as string);
  const align = mapAlign(props.align as string);

  return (
    <div className="flex flex-row box-border gap-4" style={{ justifyContent: justify, alignItems: align }}>
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

export const WvA2uiRow = createComponentImplementation(RowApi as never, RowComponent as never);
