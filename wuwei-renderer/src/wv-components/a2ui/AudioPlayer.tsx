import { createComponentImplementation } from '@a2ui/react/v0_9';
import { AudioPlayerApi } from '@a2ui/web_core/v0_9/basic_catalog';

function AudioPlayerComponent({ props }: { props: Record<string, any>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return (
    <div style={{
      display: 'flex', flexDirection: 'column',
      gap: 'var(--a2ui-spacing-xs, 0.25rem)',
      background: 'var(--a2ui-audioplayer-background, transparent)',
      borderRadius: 'var(--a2ui-audioplayer-border-radius, 0)',
      padding: 'var(--a2ui-audioplayer-padding, 0)',
    }}>
      {props.description && (
        <span style={{ fontSize: 'var(--a2ui-font-size-xs, 0.75rem)', color: 'var(--a2ui-text-caption-color, #8b949e)' }}>
          {props.description as string}
        </span>
      )}
      <audio src={props.url as string} controls style={{ width: '100%' }} />
    </div>
  );
}

export const WvA2uiAudioPlayer = createComponentImplementation(AudioPlayerApi as never, AudioPlayerComponent as never);
