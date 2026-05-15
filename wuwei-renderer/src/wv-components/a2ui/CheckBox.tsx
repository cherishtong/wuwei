import { useId, useState, useEffect } from 'react';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { CheckBoxApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Checkbox } from '@/wv-components/ui/checkbox';
import { Label } from '@/wv-components/ui/label';

function CheckBoxComponent({ props }: { props: Record<string, any>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const id = useId();
  const hasError = Array.isArray(props.validationErrors) && props.validationErrors.length > 0;
  const setValue = props.setValue as ((v: boolean) => void) | undefined;
  const [checked, setChecked] = useState(!!props.value);

  useEffect(() => {
    setChecked(!!props.value);
  }, [props.value]);

  return (
    <div className="flex flex-col gap-1 my-2">
      <div className="flex items-center gap-2">
        <Checkbox
          id={id}
          checked={checked}
          onCheckedChange={(v) => { setChecked(!!v); setValue?.(!!v); }}
          className={hasError ? 'border-destructive' : ''}
        />
        {props.label && (
          <Label htmlFor={id} className={hasError ? 'text-destructive' : ''}>
            {props.label as string}
          </Label>
        )}
      </div>
      {hasError && <span className="text-xs text-destructive ml-6">{props.validationErrors[0]}</span>}
    </div>
  );
}

export const WvA2uiCheckBox = createComponentImplementation(CheckBoxApi as never, CheckBoxComponent as never);
