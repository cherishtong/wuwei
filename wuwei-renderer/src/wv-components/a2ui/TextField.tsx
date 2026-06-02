import { useId, useState, useEffect } from 'react';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { TextFieldApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Input } from '@/wv-components/ui/input';
import { Label } from '@/wv-components/ui/label';
import { X } from 'lucide-react';

function TextFieldComponent({ props, context }: { props: Record<string, any>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const id = useId();
  const isLong = props.variant === 'longText';
  const type = props.variant === 'number' ? 'number' : props.variant === 'obscured' ? 'password' : 'text';
  const hasError = Array.isArray(props.validationErrors) && props.validationErrors.length > 0;
  const setValue = props.setValue as ((v: string) => void) | undefined;
  const clearable = props.clearable === true;
  const clearEvent = props.clearEvent as string | undefined;
  const [val, setVal] = useState((props.value as string) || '');

  useEffect(() => {
    setVal((props.value as string) || '');
  }, [props.value]);

  const handleClear = () => {
    setVal('');
    setValue?.('');
    if (clearEvent && (context as any)?.dispatchAction) {
      (context as any).dispatchAction({ event: { name: clearEvent } });
    }
  };

  return (
    <div className="flex flex-col gap-1">
      {props.label && <Label htmlFor={id}>{props.label as string}</Label>}
      {isLong ? (
        <textarea
          id={id}
          className="flex min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
          value={val}
          onChange={(e) => { setVal(e.target.value); setValue?.(e.target.value); }}
        />
      ) : (
        <div className="relative">
          <Input
            id={id}
            type={type}
            value={val}
            onChange={(e) => { setVal(e.target.value); setValue?.(e.target.value); }}
            className={`${hasError ? 'border-destructive' : ''} ${clearable && val ? 'pr-8' : ''}`}
          />
          {clearable && val && (
            <button
              type="button"
              className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              onClick={handleClear}
            >
              <X size={14} />
            </button>
          )}
        </div>
      )}
      {hasError && <span className="text-xs text-destructive">{props.validationErrors[0]}</span>}
    </div>
  );
}

export const WvA2uiTextField = createComponentImplementation(TextFieldApi as never, TextFieldComponent as never);
