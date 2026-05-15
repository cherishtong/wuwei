import { useState, useEffect } from 'react';
import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { ToggleGroup as ShadcnToggleGroup, ToggleGroupItem } from '@/wv-components/ui/toggle-group';

const ToggleGroupApi = {
  name: 'ToggleGroup',
  schema: z.object({
    items: z.array(z.object({
      value: z.string(),
      label: z.string(),
    })).describe('The toggle items.'),
    type: z.enum(['single', 'multiple']).default('single').optional().describe('Selection type.'),
    value: z.union([z.string(), z.array(z.string())]).optional().describe('Selected value(s).'),
  }).strict(),
};

function ToggleGroupComponent({ props }: { props: Record<string, unknown> & { setValue?: (val: string | string[]) => void }; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const items = (Array.isArray(props.items) ? props.items : []) as Array<{ value: string; label: string }>;
  const isSingle = (props.type as string) !== 'multiple';
  const initVal = isSingle ? ((props.value as string) || '') : (Array.isArray(props.value) ? props.value as string[] : []);
  const [value, setValue] = useState<string | string[]>(initVal);

  useEffect(() => {
    if (isSingle) setValue((props.value as string) || '');
    else setValue(Array.isArray(props.value) ? props.value as string[] : []);
  }, [props.value, isSingle]);

  const handleChange = (val: string | string[]) => {
    setValue(val);
    props.setValue?.(val);
  };

  if (!items.length) return null;

  return (
    <ShadcnToggleGroup
      type={isSingle ? 'single' : 'multiple'}
      value={value as never}
      onValueChange={handleChange as never}
    >
      {items.map((item) => (
        <ToggleGroupItem key={item.value} value={item.value}>{item.label}</ToggleGroupItem>
      ))}
    </ShadcnToggleGroup>
  );
}

export const WvA2uiToggleGroup = createComponentImplementation(ToggleGroupApi as never, ToggleGroupComponent as never);
