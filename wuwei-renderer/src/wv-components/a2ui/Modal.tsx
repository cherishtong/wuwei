import { useState } from 'react';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { ModalApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/wv-components/ui/dialog';

function ModalComponent({ props, buildChild }: { props: Record<string, any>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const [open, setOpen] = useState(false);

  return (
    <>
      <div onClick={() => setOpen(true)} className="inline-block cursor-pointer">
        {props.trigger ? buildChild(props.trigger as string) : null}
      </div>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          {props.title && <DialogTitle>{props.title as string}</DialogTitle>}
          {props.content ? buildChild(props.content as string) : null}
        </DialogContent>
      </Dialog>
    </>
  );
}

export const WvA2uiModal = createComponentImplementation(ModalApi as never, ModalComponent as never);
