import { useId } from 'react';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { SliderApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Slider as ShadcnSlider } from '@/wv-components/ui/slider';

function SliderComponent({ props }: { props: Record<string, any>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const id = useId();
  const setValue = props.setValue as ((v: number) => void) | undefined;

  return (
    <div className="flex flex-col gap-1 my-2">
      <div className="flex justify-between items-center">
        {props.label && (
          <label htmlFor={id} className="text-sm font-medium">{props.label as string}</label>
        )}
        <span className="text-xs text-muted-foreground">{props.value as number}</span>
      </div>
      <ShadcnSlider
        id={id}
        min={props.min as number ?? 0}
        max={props.max as number ?? 100}
        value={[(props.value as number) ?? 0]}
        onValueChange={([v]) => setValue?.(v)}
      />
    </div>
  );
}

export const WvA2uiSlider = createComponentImplementation(SliderApi as never, SliderComponent as never);
