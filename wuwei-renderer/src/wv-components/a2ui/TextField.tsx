import { useId } from 'react';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { TextFieldApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Input } from '@/wv-components/ui/input';
import { Label } from '@/wv-components/ui/label';

function TextFieldComponent({ props }: { props: Record<string, any>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const id = useId();
  const isLong = props.variant === 'longText';
  const type = props.variant === 'number' ? 'number' : props.variant === 'obscured' ? 'password' : 'text';
  const hasError = Array.isArray(props.validationErrors) && props.validationErrors.length > 0;
  const setValue = props.setValue as ((v: string) => void) | undefined;

  return (
    <div className="flex flex-col gap-1">
      {props.label && <Label htmlFor={id}>{props.label as string}</Label>}
      {isLong ? (
        <textarea
          id={id}
          className="flex min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
          value={(props.value as string) || ''}
          onChange={(e) => setValue?.(e.target.value)}
        />
      ) : (
        <Input
          id={id}
          type={type}
          value={(props.value as string) || ''}
          onChange={(e) => setValue?.(e.target.value)}
          className={hasError ? 'border-destructive' : ''}
        />
      )}
      {hasError && <span className="text-xs text-destructive">{props.validationErrors[0]}</span>}
    </div>
  );
}

export const WvA2uiTextField = createComponentImplementation(TextFieldApi as never, TextFieldComponent as never);
