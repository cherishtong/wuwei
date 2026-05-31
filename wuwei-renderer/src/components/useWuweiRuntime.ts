import {
  useExternalStoreRuntime,
  type ThreadMessageLike,
} from '@assistant-ui/react';

export interface DisplayMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  time?: string;
  isGenerationCard?: boolean;
  steps?: StepState[];
  log?: { time: string; action: string; path: string; detail?: string }[];
  allDone?: boolean;
  skillId?: string | null;
  error?: string | null;
  fileProgress?: string[];
}

export interface StepState {
  key: string;
  label: string;
  status: 'pending' | 'in_progress' | 'done' | 'error';
}

function parseTime(t: string): Date {
  const [h, m] = t.split(':').map(Number);
  const d = new Date();
  d.setHours(h ?? 0, m ?? 0, 0, 0);
  return d;
}

export interface WuweiRuntimeOptions {
  messages: DisplayMessage[];
  isRunning: boolean;
  onSend: (text: string) => Promise<void>;
}

export function useWuweiRuntime({ messages, isRunning, onSend }: WuweiRuntimeOptions) {
  const convertMessage = (msg: DisplayMessage): ThreadMessageLike => {
    if (msg.isGenerationCard) {
      const cardSteps = msg.steps ?? [];
      const cardDone = msg.allDone ?? false;
      const cardIsRunning = cardSteps.some(
        (s) => s.status === 'in_progress' || s.status === 'pending',
      );
      return {
        role: 'assistant',
        id: msg.id,
        content: [
          {
            type: 'data-generation-card' as `data-${string}`,
            data: {
              steps: cardSteps,
              log: msg.log ?? [],
              allDone: cardDone,
              skillId: msg.skillId ?? null,
              error: msg.error ?? null,
            },
          },
        ],
        status: cardIsRunning
          ? { type: 'running' as const }
          : { type: 'complete' as const, reason: 'stop' as const },
      };
    }
    const role = msg.role as 'user' | 'assistant' | 'system';
    return {
      role,
      id: msg.id,
      content: [{ type: 'text', text: msg.content }],
      createdAt: msg.time ? parseTime(msg.time) : undefined,
      ...(role === 'assistant'
        ? { status: { type: 'complete' as const, reason: 'stop' as const } }
        : {}),
    };
  };

  return useExternalStoreRuntime<DisplayMessage>({
    messages,
    isRunning,
    convertMessage,
    async onNew(message) {
      const text = message.content
        .filter((p): p is { type: 'text'; text: string } => p.type === 'text')
        .map((p) => p.text)
        .join('');
      if (!text.trim()) return;
      await onSend(text.trim());
    },
  });
}
