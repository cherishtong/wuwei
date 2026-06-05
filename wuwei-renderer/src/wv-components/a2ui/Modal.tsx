import React, { useState, useEffect, useRef } from 'react';
import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import * as DialogPrimitive from '@radix-ui/react-dialog';
import { cn } from '@/wv-components/ui/lib/utils';

const ModalApi = {
  name: 'Modal',
  schema: z.object({
    trigger: z.string().optional().describe('Component ID that opens the modal. Leave empty if controlled by DataModel open binding.'),
    content: z.string().describe('Component ID displayed inside the modal.'),
    title: z.string().optional().describe('Modal title.'),
    open: z.union([z.boolean(), z.object({ path: z.string() })]).optional().describe('DataModel path to control visibility. A2UI framework resolves this automatically.'),
  }).strict(),
};

function ModalComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string) => React.ReactNode; context: unknown }) {
  // A2UI framework resolves DataModel bindings — props.open is already the resolved boolean
  const resolvedOpen = typeof props.open === 'boolean' ? props.open : undefined;
  const isControlled = resolvedOpen !== undefined;
  const [internalOpen, setInternalOpen] = useState(false);
  const isOpen = isControlled ? resolvedOpen : internalOpen;
  const containerRef = useRef<HTMLDivElement>(null);

  const openModal = () => {
    if (isControlled && props.setValue) (props as any).setValue?.(true);
    else setInternalOpen(true);
  };
  const closeModal = () => {
    if (isControlled && props.setValue) (props as any).setValue?.(false);
    else setInternalOpen(false);
  };

  return (
    <div ref={containerRef}>
      {/* Trigger is rendered inline where placed in the component tree */}
      {props.trigger ? <span onClick={openModal} className="inline-block cursor-pointer">{buildChild(props.trigger as string)}</span> : null}

      <DialogPrimitive.Root open={isOpen} onOpenChange={(o) => { if (!o) closeModal(); }}>
        <DialogPrimitive.Portal container={containerRef.current}>
          <div className="fixed inset-0 z-50 bg-black/50" />
          <DialogPrimitive.Content className={cn(
            "fixed left-[50%] top-[50%] z-50 grid w-full max-w-lg translate-x-[-50%] translate-y-[-50%] gap-4 border bg-background p-6 shadow-lg rounded-lg",
          )}>
            {props.title ? <DialogPrimitive.Title className="text-lg font-semibold">{String(props.title)}</DialogPrimitive.Title> : null}
            {props.content ? buildChild(props.content as string) : null}
          </DialogPrimitive.Content>
        </DialogPrimitive.Portal>
      </DialogPrimitive.Root>
    </div>
  );
}

export const WvA2uiModal = createComponentImplementation(ModalApi as never, ModalComponent as never);
