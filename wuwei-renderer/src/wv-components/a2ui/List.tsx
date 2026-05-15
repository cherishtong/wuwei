import { createComponentImplementation } from '@a2ui/react/v0_9';
import { ListApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Fragment } from 'react';

function ListComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const children = Array.isArray(props.children) ? props.children : [];
  const isHorizontal = props.direction === 'horizontal';
  const align = mapAlign(props.align as string);

  return (
    <div
      className="flex gap-2 p-0 box-border"
      style={{
        flexDirection: isHorizontal ? 'row' : 'column',
        alignItems: align,
        overflowX: isHorizontal ? 'auto' : 'hidden',
        overflowY: isHorizontal ? 'hidden' : 'auto',
      }}
    >
      {children.map((child: string | { id: string; basePath?: string }, i: number) => {
        if (typeof child === 'string') return <Fragment key={`${child}-${i}`}>{buildChild(child)}</Fragment>;
        if (child && typeof child === 'object' && 'id' in child) return <Fragment key={`${child.id}-${i}`}>{buildChild(child.id, child.basePath)}</Fragment>;
        return null;
      })}
    </div>
  );
}

function mapAlign(a?: string) {
  switch (a) {
    case 'start': return 'flex-start'; case 'center': return 'center';
    case 'end': return 'flex-end'; case 'stretch': return 'stretch';
    default: return 'stretch';
  }
}

export const WvA2uiList = createComponentImplementation(ListApi as never, ListComponent as never);
