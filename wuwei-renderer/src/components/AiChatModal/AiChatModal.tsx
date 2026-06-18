import { type FC } from 'react';
import {
  AssistantRuntimeProvider,
  ThreadPrimitive,
  ComposerPrimitive,
  MessagePrimitive,
} from '@assistant-ui/react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/wv-components/ui/dialog';
import { Button } from '@/wv-components/ui/button';
import { cn } from '@/wv-components/ui/lib/utils';
import { useModalState, closeAiModal } from './aiModalBridge';
import { useSSEChatRuntime } from './useSSEChatRuntime';

// ── Thinking dots animation ───────────────────────────────────

const ThinkingDots: FC = () => (
  <span className="inline-flex items-center gap-1 px-1">
    {[0, 150, 300].map((delay) => (
      <span
        key={delay}
        className="w-1.5 h-1.5 rounded-full bg-foreground/40 animate-bounce"
        style={{ animationDelay: `${delay}ms` }}
      />
    ))}
  </span>
);

// ── Modal ─────────────────────────────────────────────────────

export function AiChatModal() {
  const { config, open, key } = useModalState();

  if (!config) {
    return (
      <Dialog open={false} onOpenChange={() => {}}>
        <DialogContent />
      </Dialog>
    );
  }

  return <AiChatModalInner key={key} config={config} open={open} />;
}

function AiChatModalInner({
  config,
  open,
}: {
  config: NonNullable<ReturnType<typeof useModalState>['config']>;
  open: boolean;
}) {
  const { runtime, isRunning, accept, retry, cancel } = useSSEChatRuntime(config);

  const handleOpenChange = (shouldOpen: boolean) => {
    if (!shouldOpen) {
      cancel();
      closeAiModal();
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent
        className={cn(
          'max-w-4xl w-[92vw] h-[85vh] flex flex-col p-0 gap-0',
          'bg-background',
        )}
        onInteractOutside={(e) => {
          if (isRunning) e.preventDefault();
        }}
      >
        {/* Header — show thinking indicator when running */}
        <DialogHeader className="px-4 py-3 border-b shrink-0 flex flex-row items-center gap-2">
          <DialogTitle>{config.title || 'AI 助手'}</DialogTitle>
          {isRunning && <ThinkingDots />}
        </DialogHeader>

        {/* Chat area — uses @assistant-ui/react built-in rendering */}
        <AssistantRuntimeProvider runtime={runtime}>
          <ThreadPrimitive.Root className="flex-1 flex flex-col min-h-0">
            <ThreadPrimitive.Viewport className="flex-1 overflow-y-auto px-4 py-4">
              <ThreadPrimitive.Messages>
                {({ message }) => (
                  <div
                    className={cn(
                      'flex gap-3 mb-4',
                      message.role === 'user' ? 'justify-end' : 'justify-start',
                    )}
                  >
                    <div
                      className={cn(
                        'max-w-[85%] px-4 py-2.5 rounded-2xl text-sm leading-relaxed whitespace-pre-wrap',
                        message.role === 'user'
                          ? 'bg-primary text-primary-foreground rounded-br-md'
                          : 'bg-muted text-foreground rounded-bl-md',
                      )}
                    >
                      <MessagePrimitive.Parts />
                    </div>
                  </div>
                )}
              </ThreadPrimitive.Messages>
            </ThreadPrimitive.Viewport>

            {/* Composer */}
            <div className="border-t px-4 py-3 shrink-0">
              <ComposerPrimitive.Root className="flex items-end gap-2">
                <ComposerPrimitive.Input
                  placeholder={isRunning ? 'AI 正在生成...' : '继续输入...'}
                  disabled={isRunning}
                  className="flex-1 min-h-[40px] max-h-[120px] resize-none rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:opacity-50"
                />
                <ComposerPrimitive.Send
                  disabled={isRunning}
                  className="inline-flex items-center justify-center whitespace-nowrap rounded-md text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 h-10 px-4 py-2 shrink-0 disabled:opacity-50"
                />
              </ComposerPrimitive.Root>
            </div>

            {/* Footer */}
            <div className="flex justify-end gap-2 px-4 py-3 border-t shrink-0">
              <Button variant="outline" size="sm" onClick={retry} disabled={isRunning}>
                重新生成
              </Button>
              <Button variant="ghost" size="sm" onClick={() => { cancel(); closeAiModal(); }}>
                关闭
              </Button>
              <Button size="sm" onClick={() => { accept(); closeAiModal(); }} disabled={isRunning}>
                确认
              </Button>
            </div>
          </ThreadPrimitive.Root>
        </AssistantRuntimeProvider>
      </DialogContent>
    </Dialog>
  );
}
