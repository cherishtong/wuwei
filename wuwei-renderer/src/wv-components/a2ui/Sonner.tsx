import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Toaster } from 'sonner';

const SonnerApi = {
  name: 'Sonner',
  schema: z.object({}).strict(),
};

function SonnerComponent() {
  return <Toaster />;
}

export const WvA2uiSonner = createComponentImplementation(SonnerApi as never, SonnerComponent as never);
