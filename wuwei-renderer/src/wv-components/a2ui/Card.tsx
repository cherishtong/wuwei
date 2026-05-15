import { createComponentImplementation } from '@a2ui/react/v0_9';
import { CardApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Card as ShadcnCard, CardContent } from '@/wv-components/ui/card';

function CardComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return (
    <ShadcnCard className="m-card">
      <CardContent className="p-card">
        {props.child != null ? buildChild(props.child as string) : null}
      </CardContent>
    </ShadcnCard>
  );
}

export const WvA2uiCard = createComponentImplementation(CardApi as never, CardComponent as never);
