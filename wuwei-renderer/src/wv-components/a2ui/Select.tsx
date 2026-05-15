import { useState, useEffect } from 'react';
import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Select as ShadcnSelect, SelectTrigger, SelectValue, SelectContent, SelectItem } from '@/wv-components/ui/select';

const SelectApi = {
  name: 'Select',
  schema: z.object({
    items: z.array(z.object({
      value: z.string(),
      label: z.string(),
    })).describe('The select options.'),
    value: z.string().optional().describe('The selected value.'),
    placeholder: z.string().default('Select...').optional().describe('Placeholder text when nothing is selected.'),
  }).strict(),
};

function SelectComponent({ props }: { props: Record<string, unknown> & { setValue?: (val: string) => void }; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const items = (Array.isArray(props.items) ? props.items : []) as Array<{ value: string; label: string }>;
  const [value, setValue] = useState((props.value as string) || '');

  useEffect(() => { setValue((props.value as string) || ''); }, [props.value]);

  const handleChange = (val: string) => {
    setValue(val);
    props.setValue?.(val);
  };

  if (!items.length) return null;

  return (
    <ShadcnSelect value={value} onValueChange={handleChange}>
      <SelectTrigger className="w-full">
        <SelectValue placeholder={(props.placeholder as string) || 'Select...'} />
      </SelectTrigger>
      <SelectContent>
        {items.map((item) => (
          <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>
        ))}
      </SelectContent>
    </ShadcnSelect>
  );
}

export const WvA2uiSelect = createComponentImplementation(SelectApi as never, SelectComponent as never);
