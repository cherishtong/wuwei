import { useId } from 'react';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { DateTimeInputApi } from '@a2ui/web_core/v0_9/basic_catalog';

function DateTimeInputComponent({ props }: { props: Record<string, any>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const id = useId();
  let type = 'datetime-local';
  if (props.enableDate && !props.enableTime) type = 'date';
  if (!props.enableDate && props.enableTime) type = 'time';
  const setValue = props.setValue as ((v: string) => void) | undefined;

  return (
    <div className="flex flex-col gap-1">
      {props.label && (
        <label htmlFor={id} className="text-sm font-medium">{props.label as string}</label>
      )}
      <input
        id={id}
        type={type}
        value={(props.value as string) || ''}
        onChange={(e) => setValue?.(e.target.value)}
        min={typeof props.min === 'string' ? props.min : undefined}
        max={typeof props.max === 'string' ? props.max : undefined}
        className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
      />
    </div>
  );
}

export const WvA2uiDateTimeInput = createComponentImplementation(DateTimeInputApi as never, DateTimeInputComponent as never);
