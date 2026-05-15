import { createComponentImplementation } from '@a2ui/react/v0_9';
import { AudioPlayerApi } from '@a2ui/web_core/v0_9/basic_catalog';

function AudioPlayerComponent({ props }: { props: Record<string, any>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return (
    <div className="flex flex-col gap-1 bg-transparent rounded-none p-0">
      {props.description && (
        <span className="text-xs text-muted-foreground">{props.description as string}</span>
      )}
      <audio src={props.url as string} controls className="w-full" />
    </div>
  );
}

export const WvA2uiAudioPlayer = createComponentImplementation(AudioPlayerApi as never, AudioPlayerComponent as never);
