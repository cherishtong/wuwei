import { createComponentImplementation } from '@a2ui/react/v0_9';
import { ButtonApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Button as ShadcnButton } from '@/wv-components/ui/button';

function ButtonComponent({ props, buildChild }: { props: Record<string, any>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const variant = (props.variant as string) || 'default';
  const isDisabled = props.isValid === false;
  const action = props.action as (() => void) | undefined;

  let shadcnVariant: 'default' | 'secondary' | 'outline' | 'ghost' = 'default';
  if (variant === 'primary') shadcnVariant = 'default';
  else if (variant === 'secondary') shadcnVariant = 'secondary';
  else if (variant === 'borderless') shadcnVariant = 'ghost';
  else shadcnVariant = 'outline';

  return (
    <ShadcnButton
      variant={shadcnVariant}
      disabled={isDisabled}
      onClick={() => { if (!isDisabled && action) action(); }}
    >
      {props.child != null ? buildChild(props.child as string) : null}
    </ShadcnButton>
  );
}

export const WvA2uiButton = createComponentImplementation(ButtonApi as never, ButtonComponent as never);
