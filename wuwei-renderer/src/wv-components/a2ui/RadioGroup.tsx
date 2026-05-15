import { useState, useEffect } from 'react';
import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { RadioGroup as ShadcnRadioGroup, RadioGroupItem } from '@/wv-components/ui/radio-group';
import { Label } from '@/wv-components/ui/label';

const RadioGroupApi = {
  name: 'RadioGroup',
  schema: z.object({
    items: z.array(z.object({
      value: z.string(),
      label: z.string(),
    })).describe('The radio items.'),
    value: z.string().optional().describe('The selected value.'),
  }).strict(),
};

function RadioGroupComponent({ props }: { props: Record<string, unknown> & { setValue?: (val: string) => void }; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const items = (Array.isArray(props.items) ? props.items : []) as Array<{ value: string; label: string }>;
  const [value, setValue] = useState((props.value as string) || '');

  useEffect(() => { setValue((props.value as string) || ''); }, [props.value]);

  const handleChange = (val: string) => {
    setValue(val);
    props.setValue?.(val);
  };

  if (!items.length) return null;

  return (
    <ShadcnRadioGroup value={value} onValueChange={handleChange}>
      {items.map((item) => (
        <div key={item.value} className="flex items-center space-x-2">
          <RadioGroupItem value={item.value} id={`radio-${item.value}`} />
          <Label htmlFor={`radio-${item.value}`}>{item.label}</Label>
        </div>
      ))}
    </ShadcnRadioGroup>
  );
}

export const WvA2uiRadioGroup = createComponentImplementation(RadioGroupApi as never, RadioGroupComponent as never);
