import { createComponentImplementation } from '@a2ui/react/v0_9';
import { CardApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Card as ShadcnCard } from '@/wv-components/ui/card';

function CardComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return (
    <ShadcnCard className="w-full">
      <div className="p-6">
        {props.child != null ? buildChild(props.child as string) : null}
      </div>
    </ShadcnCard>
  );
}

export const WvA2uiCard = createComponentImplementation(CardApi as never, CardComponent as never);
