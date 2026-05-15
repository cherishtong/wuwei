import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { TooltipRoot, TooltipContent, TooltipTrigger } from '@/wv-components/ui/tooltip';

const TooltipApi = {
  name: 'Tooltip',
  schema: z.object({
    trigger: z.string().describe('The ID of the trigger component.'),
    tooltip: z.string().describe('The tooltip text to display.'),
  }).strict(),
};

function TooltipComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return (
    <TooltipRoot>
      <TooltipTrigger className="cursor-pointer">
        {props.trigger != null ? buildChild(props.trigger as string) : null}
      </TooltipTrigger>
      <TooltipContent>
        <p>{props.tooltip as string}</p>
      </TooltipContent>
    </TooltipRoot>
  );
}

export const WvA2uiTooltip = createComponentImplementation(TooltipApi as never, TooltipComponent as never);
