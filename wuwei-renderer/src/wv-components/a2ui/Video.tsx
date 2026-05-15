import { createComponentImplementation } from '@a2ui/react/v0_9';
import { VideoApi } from '@a2ui/web_core/v0_9/basic_catalog';

function VideoComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  return <video src={props.url as string} controls className="w-full h-auto rounded-none" />;
}

export const WvA2uiVideo = createComponentImplementation(VideoApi as never, VideoComponent as never);
