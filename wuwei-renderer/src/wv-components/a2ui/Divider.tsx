import { createComponentImplementation } from '@a2ui/react/v0_9';
import { DividerApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Separator } from '@/wv-components/ui/separator';

function DividerComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return <Separator orientation={(props.axis === 'vertical' ? 'vertical' : 'horizontal') as never} />;
}

export const WvA2uiDivider = createComponentImplementation(DividerApi as never, DividerComponent as never);
