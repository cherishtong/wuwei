import { z } from 'zod';
import React from 'react';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import {
  Sheet, SheetTrigger, SheetContent,
  SheetHeader, SheetTitle, SheetClose,
} from '@/wv-components/ui/sheet';

const SheetApi = {
  name: 'Sheet',
  schema: z.object({
    trigger: z.string().optional(),
    content: z.string(),
    title: z.string().optional(),
    side: z.enum(['top', 'bottom', 'left', 'right']).default('right').optional(),
    open: z.boolean().optional().default(false),
    hideClose: z.boolean().optional().default(false),
  }).strict(),
};

const CLOSE_EVENT = 'wuwei-sheet-close';

// Global listener: converts wuwei-sheet-close → a2ui-patch
window.addEventListener(CLOSE_EVENT, function(e) {
  const { id } = (e as CustomEvent).detail || {};
  const skillId = (window as any).__wuwei_activeSkillId || 'resume-builder';
  if (id && skillId) {
    window.dispatchEvent(new CustomEvent('a2ui-patch', {
      detail: { skillId, threadId: null, patches: [{ id, open: false }] },
    }));
  }
});

function SheetComponent({ props, buildChild, context }: {
  props: Record<string, unknown>;
  buildChild: (id: string, basePath?: string) => React.ReactNode;
  context: unknown;
}) {
  const side = (props.side as string) || 'right';
  const hasTrigger = !!props.trigger;
  const cid = (context as { componentModel?: { id?: string } })?.componentModel?.id || '';
  const isOpen = props.open === true;
  const hideClose = props.hideClose === true;

  const handleOpenChange = (o: boolean) => {
    if (!o && cid) {
      window.dispatchEvent(new CustomEvent(CLOSE_EVENT, { detail: { id: cid } }));
    }
  };

  const isHorizontal = side === 'left' || side === 'right';
  const widthStyle = isHorizontal
    ? { maxWidth: '50vw', width: '50vw', height: '100vh' }
    : { maxHeight: '80vh', height: '80vh' };

  return (
    <Sheet open={isOpen} onOpenChange={handleOpenChange}>
      {hasTrigger ? (
        <SheetTrigger className="cursor-pointer">
          {buildChild(props.trigger as string)}
        </SheetTrigger>
      ) : null}
      <SheetContent
        side={side as 'top' | 'bottom' | 'left' | 'right'}
        style={widthStyle}
        className={(hideClose ? '[&>button.absolute]:hidden ' : '') + '!p-0'}
      >
        {(!!props.title) ? (
          <SheetHeader>
            {!!props.title && <SheetTitle>{props.title as string}</SheetTitle>}
          </SheetHeader>
        ) : null}
        {props.content != null ? buildChild(props.content as string) : null}
      </SheetContent>
    </Sheet>
  );
}

export const WvA2uiSheet = createComponentImplementation(SheetApi as never, SheetComponent as never);
