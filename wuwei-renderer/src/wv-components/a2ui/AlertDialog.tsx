import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import {
  AlertDialog,
  AlertDialogTrigger,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogCancel,
  AlertDialogAction,
} from '@/wv-components/ui/alert-dialog';

const AlertDialogApi = {
  name: 'AlertDialog',
  schema: z.object({
    trigger: z.string().describe('The ID of the trigger component.'),
    content: z.string().optional().describe('The ID of the content component to display in the dialog body.'),
    title: z.string().describe('The title of the alert dialog.'),
    description: z.string().describe('The description text.').optional(),
    confirmLabel: z.string().default('Continue').optional().describe('Label for the confirm button.'),
    cancelLabel: z.string().default('Cancel').optional().describe('Label for the cancel button.'),
  }).strict(),
};

function AlertDialogComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return (
    <AlertDialog>
      <AlertDialogTrigger className="cursor-pointer">
        {props.trigger != null ? buildChild(props.trigger as string) : null}
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{props.title as string}</AlertDialogTitle>
          {!!props.description && <AlertDialogDescription>{props.description as string}</AlertDialogDescription>}
        </AlertDialogHeader>
        {props.content != null ? buildChild(props.content as string) : null}
        <AlertDialogFooter>
          <AlertDialogCancel>{(props.cancelLabel as string) || 'Cancel'}</AlertDialogCancel>
          <AlertDialogAction>{(props.confirmLabel as string) || 'Continue'}</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

export const WvA2uiAlertDialog = createComponentImplementation(AlertDialogApi as never, AlertDialogComponent as never);
