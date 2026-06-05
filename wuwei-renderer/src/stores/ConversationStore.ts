import { kernel } from '../kernel';

export interface ChatMessage {
  id: string;
  role: 'user' | 'system' | 'skill-event' | 'step' | 'handoff' | 'assistant';
  content: string;
  time: string;
  seq?: number;
  // step messages (legacy, replaced by aggregated generation messages)
  key?: string;
  label?: string;
  status?: string;
  // skill-event / handoff
  referenceId?: string;
  fromSkillId?: string;
  toSkillId?: string;
  // generation card (aggregated by kernel, sent as single updatable message)
  msgType?: string;     // "generation"
  steps?: import('../components/useWuweiRuntime').StepState[];
  log?: { time: string; action: string; path: string; detail?: string }[];
  skillId?: string | null;
  allDone?: boolean;
  error?: string | null;
  fileProgress?: string[];
}

export interface Conversation {
  id: string;
  title: string;
  skillId?: string;
  skillName?: string;
  activeSkillId?: string;
  messages: ChatMessage[];
  createdAt: number;
  updatedAt: number;
}

// Local cache — populated async from kernel, read synchronously by components
let homeCache: Conversation | null = null;
let listCache: Conversation[] = [];
let ready = false;
let initPromise: Promise<void> | null = null;

const listeners: (() => void)[] = [];

function notify() {
  listeners.forEach((fn) => fn());
}

function mid(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

function timeStr(): string {
  return new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

function normalizeMessage(m: any): ChatMessage {
  return {
    id: m.id as string,
    role: m.role as ChatMessage['role'],
    content: (m.content as string) || '',
    time: (m.time as string) || '',
    seq: m.seq as number | undefined,
    key: m.key as string | undefined,
    label: m.label as string | undefined,
    status: m.status as string | undefined,
    referenceId: (m.referenceId || m.skillId) as string | undefined,
    fromSkillId: m.fromSkillId as string | undefined,
    toSkillId: m.toSkillId as string | undefined,
    // Generation card fields (from meta JSON spread by kernel)
    msgType: m.type as string | undefined,
    steps: m.steps as ChatMessage['steps'],
    log: m.log as ChatMessage['log'],
    skillId: m.skillId as string | null | undefined,
    allDone: m.allDone as boolean | undefined,
    error: m.error as string | null | undefined,
    fileProgress: m.fileProgress as string[] | undefined,
  };
}

function normalize(raw: Record<string, any>): Conversation {
  return {
    id: raw.id as string,
    title: raw.title as string,
    skillId: (raw.skillId as string) || undefined,
    skillName: (raw.skillName as string) || undefined,
    activeSkillId: (raw.activeSkillId as string) || undefined,
    messages: ((raw.messages as any[]) || []).map(normalizeMessage),
    createdAt: (raw.createdAt as number) * 1000,
    updatedAt: (raw.updatedAt as number) * 1000,
  };
}

async function loadFromKernel() {
  try {
    const [home, list] = await Promise.all([
      kernel.getHomeConversation(),
      kernel.listConversations(),
    ]);
    homeCache = normalize(home);
    listCache = (list as Array<Record<string, any>>).map(normalize);
    ready = true;
  } catch (e) {
    console.warn('[ConversationStore] Failed to load from kernel, using defaults:', e);
    homeCache = {
      id: mid(),
      title: '首页对话',
      messages: [],
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    listCache = [homeCache];
    ready = true;
  }
  notify();
}

function onKernelReady() {
  if (!initPromise) {
    initPromise = loadFromKernel();
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('kernel-ready', onKernelReady);
}

export const conversationStore = {
  onChange(fn: () => void) {
    listeners.push(fn);
    return () => {
      for (let i = listeners.length - 1; i >= 0; i--) {
        if (listeners[i] === fn) listeners.splice(i, 1);
      }
    };
  },

  isReady(): boolean {
    return ready;
  },

  async ensureReady(): Promise<void> {
    if (ready) return;
    if (!initPromise) {
      initPromise = loadFromKernel();
    }
    await initPromise;
  },

  // ── Synchronous reads (call ensureReady first) ──

  getHome(): Conversation {
    return homeCache!;
  },

  getHomeId(): string {
    return homeCache?.id ?? '';
  },

  get(id: string): Conversation | null {
    if (homeCache?.id === id) return homeCache;
    return listCache.find((c) => c.id === id) ?? null;
  },

  list(): Conversation[] {
    return listCache;
  },

  /** Dispatch a namespace-routed message from the kernel. */
  dispatch(msg: Record<string, unknown>) {
    const type = msg.type as string;
    switch (type) {
      case 'conversation-update': {
        const threadId = msg.threadId as string;
        const messages = (msg.messages as any[]) || [];
        const conv = threadId === homeCache?.id ? homeCache : listCache.find((c) => c.id === threadId);
        if (conv) {
          conv.messages = messages.map(normalizeMessage);
          conv.updatedAt = Date.now();
          notify();
        }
        break;
      }
      case 'message-updated': {
        const threadId = msg.threadId as string;
        const raw = msg.message as Record<string, any> | undefined;
        if (!raw) break;
        // DEBUG: trace generation card updates
        if (raw.type === 'generation') {
          console.warn('[ConvStore] message-updated gen-card id=' + raw.id + ' allDone=' + raw.allDone + ' steps=' + JSON.stringify((raw.steps as any[])?.map((s: any) => s.key + ':' + s.status)));
        }
        const conv = threadId === homeCache?.id ? homeCache : listCache.find((c) => c.id === threadId);
        if (conv) {
          const mapped = normalizeMessage(raw);
          const idx = conv.messages.findIndex((m) => m.id === mapped.id);
          if (idx >= 0) {
            conv.messages = [...conv.messages.slice(0, idx), mapped, ...conv.messages.slice(idx + 1)];
          } else {
            conv.messages = [...conv.messages, mapped];
          }
          conv.updatedAt = Date.now();
          notify();
        } else {
          console.warn('[ConvStore] message-updated for unknown thread: ' + threadId + ' (home=' + homeCache?.id + ')');
        }
        break;
      }
      case 'conversation-list': {
        const convs = ((msg.conversations as any[]) || []).map(normalize);
        // Merge: preserve existing messages for conversations already in cache.
        // Kernel listConversations() sends empty messages arrays, so replacing
        // listCache wholesale would wipe messages that arrived via message-updated.
        const existing = new Map(listCache.map((c) => [c.id, c]));
        listCache = convs.map((c: Conversation) => {
          const old = existing.get(c.id);
          if (old && old.messages.length > 0) {
            c.messages = old.messages;
            c.activeSkillId = c.activeSkillId ?? old.activeSkillId;
          }
          return c;
        });
        notify();
        break;
      }
    }
  },

  /** Replace all messages for a conversation (used by conversation-update). */
  replaceMessages(convId: string, messages: ChatMessage[]) {
    const conv = convId === homeCache?.id ? homeCache : listCache.find((c) => c.id === convId);
    if (conv) {
      conv.messages = messages;
      conv.updatedAt = Date.now();
      notify();
    }
  },

  // ── Async mutations ──

  /** Load full conversation (with messages) from kernel and update cache. */
  async loadConversation(convId: string): Promise<Conversation | null> {
    await this.ensureReady();
    try {
      const raw = await kernel.getConversation(convId);
      if (!raw) return null;
      const conv = normalize(raw);
      // Update in cache
      const idx = listCache.findIndex((c) => c.id === conv.id);
      if (idx >= 0) {
        listCache[idx] = conv;
      } else {
        listCache.unshift(conv);
      }
      if (homeCache?.id === conv.id) {
        homeCache = conv;
      }
      notify();
      return conv;
    } catch (e) {
      console.warn('[ConversationStore] Failed to load conversation:', e);
      return null;
    }
  },

  /** Find existing conversation matching the skill context, or create a new one. */
  async findOrCreate(skillContext?: { skillId: string; skillName: string }): Promise<Conversation> {
    await this.ensureReady();
    const raw = await kernel.findOrCreateConversation(
      skillContext?.skillId,
      skillContext?.skillName,
    );
    const conv = normalize(raw);
    // Update in cache (replace if exists, add if new)
    const idx = listCache.findIndex((c) => c.id === conv.id);
    if (idx >= 0) {
      listCache[idx] = conv;
    } else {
      listCache.unshift(conv);
    }
    notify();
    return conv;
  },

  async create(skillContext?: { skillId: string; skillName: string }): Promise<Conversation> {
    await this.ensureReady();
    const raw = await kernel.createConversation(
      skillContext?.skillId,
      skillContext?.skillName,
    );
    const conv = normalize(raw);
    listCache.unshift(conv);
    notify();
    return conv;
  },

  async delete(convId: string): Promise<void> {
    listCache = listCache.filter((c) => c.id !== convId);
    notify();
    try {
      await kernel.deleteConversation(convId);
    } catch (e) {
      console.warn('[ConversationStore] Failed to delete conversation:', e);
    }
  },

  async updateActiveSkill(convId: string, skillId: string | null): Promise<void> {
    const conv = convId === homeCache?.id ? homeCache : listCache.find((c) => c.id === convId);
    if (conv) {
      conv.activeSkillId = skillId ?? undefined;
      conv.updatedAt = Date.now();
      notify();
    }
    try {
      await kernel.setThreadActiveSkill(convId, skillId);
    } catch (e) {
      console.warn('[ConversationStore] Failed to update active skill:', e);
    }
  },

  /** Update activeSkillId locally without calling the kernel (kernel is the source of truth). */
  updateActiveSkillSilent(convId: string, skillId: string | null) {
    const conv = convId === homeCache?.id ? homeCache : listCache.find((c) => c.id === convId);
    if (conv) {
      conv.activeSkillId = skillId ?? undefined;
      conv.updatedAt = Date.now();
      notify();
    }
  },

  mid,

  timeStr,
};
