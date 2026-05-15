import { useState, useEffect } from 'react';
import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Toggle as ShadcnToggle } from '@/wv-components/ui/toggle';

const ToggleApi = {
  name: 'Toggle',
  schema: z.object({
    label: z.string().describe('The label to display inside the toggle.'),
    pressed: z.boolean().default(false).optional().describe('Whether the toggle is pressed.'),
  }).strict(),
};

function ToggleComponent({ props }: { props: Record<string, unknown> & { setValue?: (val: boolean) => void }; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const [pressed, setPressed] = useState(!!props.pressed);

  useEffect(() => { setPressed(!!props.pressed); }, [props.pressed]);

  return (
    <ShadcnToggle
      pressed={pressed}
      onPressedChange={(v) => { setPressed(v); props.setValue?.(v); }}
    >
      {props.label as string}
    </ShadcnToggle>
  );
}

export const WvA2uiToggle = createComponentImplementation(ToggleApi as never, ToggleComponent as never);
