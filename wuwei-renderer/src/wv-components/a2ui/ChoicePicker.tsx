import { useState, useEffect } from 'react';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { ChoicePickerApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Badge } from '@/wv-components/ui/badge';
import { Input } from '@/wv-components/ui/input';
import { cn } from '@/wv-components/ui/lib/utils';

function ChoicePickerComponent({ props }: { props: Record<string, any>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const [filter, setFilter] = useState('');
  const setValue = props.setValue as ((v: string[]) => void) | undefined;
  const initValues = Array.isArray(props.value) ? props.value : [];
  const [values, setValues] = useState<string[]>(initValues);
  const isMutuallyExclusive = props.variant === 'mutuallyExclusive';
  const displayChips = props.displayStyle === 'chips';

  useEffect(() => {
    setValues(Array.isArray(props.value) ? props.value : []);
  }, [props.value]);

  const options = ((Array.isArray(props.options) ? props.options : []) as Array<{ label: string; value: string }>)
    .filter((opt) => !props.filterable || filter === '' || String(opt.label).toLowerCase().includes(filter.toLowerCase()));

  const onToggle = (val: string) => {
    if (isMutuallyExclusive) {
      setValues([val]);
      setValue?.([val]);
    } else {
      const newValues = values.includes(val) ? values.filter((v) => v !== val) : [...values, val];
      setValues(newValues);
      setValue?.(newValues);
    }
  };

  return (
    <div className="flex flex-col gap-2">
      {props.label && <strong className="text-sm font-bold">{props.label as string}</strong>}
      {props.filterable && (
        <Input
          type="text"
          placeholder="Filter options..."
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          className="h-8 text-sm"
        />
      )}
      <div className={cn('flex flex-wrap gap-1', !displayChips && 'flex-col')}>
        {options.map((opt, i) => {
          const isSelected = values.includes(opt.value);
          if (displayChips) {
            return (
              <Badge
                key={i}
                variant={isSelected ? 'default' : 'outline'}
                className="cursor-pointer"
                onClick={() => onToggle(opt.value)}
              >
                {opt.label}
              </Badge>
            );
          }
          return (
            <label key={i} className="flex items-center gap-2 cursor-pointer text-sm">
              <input
                type={isMutuallyExclusive ? 'radio' : 'checkbox'}
                checked={isSelected}
                onChange={() => onToggle(opt.value)}
                className="h-4 w-4"
              />
              {opt.label}
            </label>
          );
        })}
      </div>
    </div>
  );
}

export const WvA2uiChoicePicker = createComponentImplementation(ChoicePickerApi as never, ChoicePickerComponent as never);
