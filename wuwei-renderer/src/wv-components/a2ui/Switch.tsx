import { useState, useEffect } from 'react';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { z } from 'zod';
import { Switch as ShadcnSwitch } from '@/wv-components/ui/switch';
import { Label } from '@/wv-components/ui/label';

const DynamicStringSchema = z.union([
  z.string(),
  z.object({ path: z.string() }),
]);

export const SwitchApi = {
  name: 'Switch',
  schema: z.object({
    label: DynamicStringSchema.describe('Label text next to the switch.'),
    value: z.boolean().default(false).describe('Whether the switch is on (true) or off (false).'),
  }).strict(),
};

function SwitchComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string) => React.ReactNode; context: unknown }) {
  const label = typeof props.label === 'string' ? props.label : String(props.label ?? '');
  const setValue = props.setValue as ((v: boolean) => void) | undefined;
  const [checked, setChecked] = useState(props.value === true);

  useEffect(() => {
    setChecked(props.value === true);
  }, [props.value]);

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
      <ShadcnSwitch
        checked={checked}
        onCheckedChange={(v) => { setChecked(v); setValue?.(v); }}
      />
      <Label>{label}</Label>
    </div>
  );
}

export const WvA2uiSwitch = createComponentImplementation(SwitchApi as never, SwitchComponent as never);
