import { useState, useEffect, useMemo, useCallback } from 'react';
import {
  AssistantRuntimeProvider,
  ThreadPrimitive,
  MessagePrimitive,
  ComposerPrimitive,
  makeAssistantDataUI,
  type DataMessagePartComponent,
} from '@assistant-ui/react';
import { useTheme } from '../contexts/ThemeContext';
import {
  conversationStore,
  type ChatMessage,
} from '../stores/ConversationStore';
import { systemStore, type SkillMeta } from '../stores/SystemStore';
import { kernel } from '../kernel';
import { useWuweiRuntime, type StepState, type DisplayMessage } from './useWuweiRuntime';
import { Popover, PopoverTrigger, PopoverContent } from '@/wv-components/ui/popover';
import {
  Command,
  CommandInput,
  CommandList,
  CommandEmpty,
  CommandGroup,
  CommandItem,
} from '@/wv-components/ui/command';
import {
  Dialog,
  DialogTrigger,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogClose,
} from '@/wv-components/ui/dialog';
import {
  AlertDialog,
  AlertDialogTrigger,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogCancel,
  AlertDialogAction,
} from '@/wv-components/ui/alert-dialog';
import { Button } from '@/wv-components/ui/button';
import { Input } from '@/wv-components/ui/input';
import logo from '../assets/logo.png';
import wei from '../assets/wei.png';

interface WuweiChatProps {
  threadId: string;
  onThreadChange: (threadId: string) => void;
}

// ──
// Custom GenerationCard data component registered with makeAssistantDataUI
// ────────────────────────────────────────────────────────────────────

const GenerationCardComponent: DataMessagePartComponent<{
  steps: StepState[];
  allDone: boolean;
  skillId?: string | null;
}> = ({ data }) => {
  const { steps, allDone, skillId } = data;
  const { resolved: theme } = useTheme();
  const isDark = theme === 'dark';

  const cardAccent = 'hsl(var(--primary))';

  if (!steps || steps.length === 0) return null;

  return (
    <div
      className="relative flex flex-col gap-3 rounded-xl text-sm overflow-hidden"
      style={{
        background: 'hsl(var(--card))',
        minWidth: 260,
        boxShadow: isDark
          ? '0px -16px 24px 0px rgba(255,255,255,0.06) inset'
          : '0px -2px 8px 0px rgba(0,0,0,0.03) inset',

      }}
    >
      <div className="relative z-10">
        <span
          className="font-semibold"
          style={{ fontSize: '1rem', color: 'hsl(var(--card-foreground))' }}
        >
          {allDone ? '生成完成' : '正在生成'}
        </span>
        <p
          style={{
            marginTop: '0.25rem',
            fontSize: '0.75rem',
            color: 'hsl(var(--muted-foreground))',
          }}
        >
          {allDone ? '技能已就绪，可以查看和使用' : '正在通过 AI 创建你的技能...'}
        </p>
      </div>

      <hr
        style={{
          width: '100%',
          height: '0.1rem',
          background: 'hsl(var(--border))',
          border: 'none',
          position: 'relative',
          zIndex: 10,
        }}
      />

      <ul className="relative z-10 flex flex-col gap-2">
        {steps.map((s: StepState) => (
          <li key={s.key} className="flex items-center gap-2">
            <span
              style={{
                fontSize: '0.75rem',
                color:
                  s.status === 'done'
                    ? 'hsl(var(--muted-foreground))'
                    : s.status === 'in_progress'
                      ? 'hsl(var(--card-foreground))'
                      : s.status === 'error'
                        ? 'hsl(0, 80%, 60%)'
                        : 'hsl(var(--muted-foreground) / 0.4)',
              }}
            >
              {s.label}
            </span>
          </li>
        ))}
      </ul>

      {allDone && skillId && (
        <button
          className="relative z-10 mt-1 w-full py-2 rounded-lg text-sm font-medium transition-colors"
          style={{
            background: cardAccent,
            color: '#fff',
          }}
          onClick={() => {
            window.dispatchEvent(
              new CustomEvent('view-active-skill', { detail: { skillId } }),
            );
          }}
        >
          使用技能
        </button>
      )}
    </div>
  );
};

const GenCardUI = makeAssistantDataUI({
  name: 'generation-card',
  render: GenerationCardComponent,
});

// ────────────────────────────────────────────────────────────────────
// Main component
// ────────────────────────────────────────────────────────────────────

export function WuweiChat({
  threadId,
  onThreadChange,
}: WuweiChatProps) {
  const { resolved: theme } = useTheme();
  const isDark = theme === 'dark';

  const [rawMessages, setRawMessages] = useState<ChatMessage[]>([]);
  const [threads, setThreads] = useState<Array<{ id: string; title: string; updatedAt: number }>>([]);
  const [selectedSkill, setSelectedSkill] = useState<{ id: string; name: string } | null>(null);
  const [availableSkills, setAvailableSkills] = useState<SkillMeta[]>([]);
  const [skillPickerOpen, setSkillPickerOpen] = useState(false);
  const [renameDialogOpen, setRenameDialogOpen] = useState(false);
  const [renameId, setRenameId] = useState<string | null>(null);
  const [renameTitle, setRenameTitle] = useState('');
  const [deleteConfirm, setDeleteConfirm] = useState<{ id: string; title: string } | null>(null);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; convId: string; title: string } | null>(null);

  const systemBubbleBg = isDark ? '#27272a' : '#f5f2eb';
  const inputBg = isDark ? '#1c1c1e' : '#f5f2eb';

  // Load messages when threadId changes — loadConversation fetches from
  // kernel DB, since non-home conversations start with empty cache.
  useEffect(() => {
    conversationStore.ensureReady().then(async () => {
      const conv = await conversationStore.loadConversation(threadId);
      setRawMessages(conv?.messages ?? []);
    });
  }, [threadId]);

  // Listen for store changes
  useEffect(() => {
    return conversationStore.onChange(() => {
      const conv = conversationStore.get(threadId);
      if (conv) {
        setRawMessages(conv.messages);
      }
      // Refresh thread list
      setThreads(
        conversationStore.list().map((c) => ({
          id: c.id,
          title: c.title,
          updatedAt: c.updatedAt,
        })),
      );
    });
  }, [threadId]);

  // Refresh thread list on mount
  useEffect(() => {
    conversationStore.ensureReady().then(() => {
      setThreads(
        conversationStore.list().map((c) => ({
          id: c.id,
          title: c.title,
          updatedAt: c.updatedAt,
        })),
      );
    });
  }, []);

  // Sync skill list from SystemStore
  useEffect(() => {
    setAvailableSkills([...systemStore.getSkills()]);
    return systemStore.onChange(() => {
      setAvailableSkills([...systemStore.getSkills()]);
    });
  }, []);

  // Clear selected skill when thread changes
  useEffect(() => {
    setSelectedSkill(null);
  }, [threadId]);

  // Clear selected skill if it gets uninstalled
  useEffect(() => {
    if (!selectedSkill) return;
    const exists = availableSkills.some((s) => s.id === selectedSkill.id);
    if (!exists) setSelectedSkill(null);
  }, [availableSkills, selectedSkill]);

  // Close context menu on any click outside
  useEffect(() => {
    if (!contextMenu) return;
    const close = () => setContextMenu(null);
    window.addEventListener('click', close);
    return () => window.removeEventListener('click', close);
  }, [contextMenu]);

  const activeSkills = useMemo(
    () => availableSkills.filter((s) => s.status === 'running'),
    [availableSkills],
  );

  // Messages are now display-ready from the kernel —
  // generation messages are single updatable records, no grouping needed.
  const { displayMessages, isRunning } = useMemo(() => {
    const items: DisplayMessage[] = [];
    let running = false;

    for (const msg of rawMessages) {
      if (msg.role === 'user') {
        items.push({
          id: msg.id, role: 'user', content: msg.content, time: msg.time,
        });
      } else if (msg.msgType === 'generation' && msg.steps) {
        const allDone = msg.allDone ?? msg.steps.every(s => s.status === 'done');
        if (!allDone) running = true;
        items.push({
          id: msg.id,
          role: 'assistant',
          content: '',
          isGenerationCard: true,
          steps: msg.steps,
          allDone,
          skillId: msg.skillId ?? null,
        });
      } else if (msg.role === 'assistant' || msg.role === 'system') {
        items.push({
          id: msg.id, role: 'assistant', content: msg.content, time: msg.time,
        });
      }
      // Legacy step/skill-event messages are silently skipped —
      // they've been replaced by aggregated generation cards.
    }

    return { displayMessages: items, isRunning: running };
  }, [rawMessages]);

  // Send handler — routes to refineSkill when a skill is @-selected
  const onSend = useCallback(
    async (text: string) => {
      if (selectedSkill) {
        console.log('[WuweiChat] onSend refineSkill=', selectedSkill.id, 'text=', text, 'threadId=', threadId);
        await kernel.refineSkill(selectedSkill.id, text, threadId);
      } else {
        console.log('[WuweiChat] onSend text=', text, 'threadId=', threadId);
        await kernel.sendIntent(text, threadId);
      }
    },
    [threadId, selectedSkill],
  );

  const runtime = useWuweiRuntime({
    messages: displayMessages,
    isRunning,
    onSend,
  });

  // New thread
  const handleNewThread = useCallback(async () => {
    const conv = await conversationStore.create();
    onThreadChange(conv.id);
  }, [onThreadChange]);

  // Open rename dialog
  const openRenameDialog = useCallback((id: string, currentTitle: string) => {
    setRenameId(id);
    setRenameTitle(currentTitle);
    setRenameDialogOpen(true);
  }, []);

  // Confirm rename
  const confirmRename = useCallback(async () => {
    if (renameId && renameTitle.trim()) {
      await kernel.updateConversationTitle(renameId, renameTitle.trim());
      const conv = conversationStore.get(renameId);
      if (conv) {
        conv.title = renameTitle.trim();
        setThreads(
          conversationStore.list().map((c) => ({
            id: c.id,
            title: c.title,
            updatedAt: c.updatedAt,
          })),
        );
      }
    }
    setRenameDialogOpen(false);
    setRenameId(null);
    setRenameTitle('');
  }, [renameId, renameTitle]);

  // Confirm delete
  const confirmDelete = useCallback(async () => {
    if (deleteConfirm) {
      await conversationStore.delete(deleteConfirm.id);
      const remaining = conversationStore.list();
      if (deleteConfirm.id === threadId && remaining.length > 0) {
        onThreadChange(remaining[0].id);
      }
      setDeleteConfirm(null);
      setThreads(
        conversationStore.list().map((c) => ({
          id: c.id,
          title: c.title,
          updatedAt: c.updatedAt,
        })),
      );
    }
  }, [deleteConfirm, threadId, onThreadChange]);

  const hasContent = rawMessages.length > 0 || isRunning;
  const isNewConversation = !hasContent;

  // Time formatter for thread list
  const fmtTime = (ts: number) => {
    const d = new Date(ts);
    const now = new Date();
    if (d.toDateString() === now.toDateString()) {
      return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    }
    return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
  };

  return (
    <div className="flex h-full bg-background">
      {/* ── Sidebar (custom, not assistant-ui ThreadList) ── */}
      <div
        className="w-56 flex-shrink-0 flex flex-col border-r overflow-hidden"
        style={{ borderColor: 'hsl(var(--border))' }}
      >
        {/* New thread button */}
        <div className="p-3">
          <button
            onClick={handleNewThread}
            className="w-full py-1.5 px-3 text-sm font-medium rounded-lg border transition-colors hover:bg-accent"
            style={{
              borderColor: 'hsl(var(--border))',
              color: 'hsl(var(--foreground))',
            }}
          >
            + 新对话
          </button>
        </div>

        {/* Thread list */}
        <div className="flex-1 overflow-y-auto px-2 pb-2">
          {threads.map((t) => (
            <button
              key={t.id}
              onClick={() => onThreadChange(t.id)}
              onContextMenu={(e) => {
                e.preventDefault();
                setContextMenu({ x: e.clientX, y: e.clientY, convId: t.id, title: t.title });
              }}
              className={`w-full text-left px-3 py-2 rounded-lg mb-0.5 text-sm transition-colors ${
                t.id === threadId
                  ? 'bg-accent text-accent-foreground'
                  : 'hover:bg-muted text-muted-foreground'
              }`}
            >
              <div className="flex items-center justify-between gap-1">
                <span className="truncate">{t.title}</span>
                <span className="text-[10px] opacity-50 flex-shrink-0">
                  {fmtTime(t.updatedAt)}
                </span>
              </div>
            </button>
          ))}
        </div>
      </div>

      {/* ── Thread (assistant-ui) ── */}
      <div className="flex-1 flex flex-col min-w-0">
        <AssistantRuntimeProvider runtime={runtime}>
          <GenCardUI />

          <ThreadPrimitive.Root className="flex flex-col h-full">
            {/* Messages viewport */}
            <ThreadPrimitive.Viewport className="flex-1 overflow-y-auto px-4 py-4 space-y-3">
              {isNewConversation && (
                <div className="flex flex-col items-center gap-3 pt-8 pb-4">
                  <img
                    src={logo}
                    alt="Wuwei"
                    className="w-12 h-12 rounded-xl object-cover opacity-80"
                  />
                  <p className="text-sm text-muted-foreground text-center">
                    描述你想要的应用，无为将为你生成
                  </p>
                </div>
              )}

              <ThreadPrimitive.Messages>
                {({ message }) => {
                  if (message.role === 'user') {
                    return (
                      <div className="flex justify-end gap-3">
                        <div className="max-w-[80%]">
                          <div
                            className="px-4 py-2.5 rounded-2xl rounded-br-md text-sm leading-relaxed whitespace-pre-line"
                            style={{
                              background: isDark ? '#e8e8ed' : 'hsl(var(--foreground))',
                              color: isDark ? '#1a1a1a' : 'hsl(var(--background))',
                            }}
                          >
                            <MessagePrimitive.Parts />
                          </div>
                        </div>
                        <img
                          src={wei}
                          alt="User"
                          className="w-7 h-7 rounded-lg flex-shrink-0 object-cover mt-0.5"
                        />
                      </div>
                    );
                  }
                  // assistant or system message — including GenerationCard
                  return (
                    <div className="flex gap-3">
                      <img
                        src={logo}
                        alt="Wuwei"
                        className="w-7 h-7 rounded-lg flex-shrink-0 object-cover mt-0.5"
                      />
                      <div className="max-w-[80%]">
                        <div
                          className="px-4 py-2.5 rounded-2xl rounded-bl-md text-sm leading-relaxed whitespace-pre-line"
                          style={{
                            background: systemBubbleBg,
                            color: 'hsl(var(--foreground))',
                          }}
                        >
                          <MessagePrimitive.Parts />
                        </div>
                      </div>
                    </div>
                  );
                }}
              </ThreadPrimitive.Messages>

              <ThreadPrimitive.ViewportFooter />
            </ThreadPrimitive.Viewport>

            {/* Composer */}
            <div className="flex-shrink-0 px-4 py-3 border-t" style={{ borderColor: 'hsl(var(--border) / 0.3)' }}>
              <ComposerPrimitive.Root className="flex items-end gap-2">
                <div
                  className="flex-1 flex flex-col rounded-2xl border px-3 py-2"
                  style={{ background: inputBg, borderColor: 'hsl(var(--border))', color: 'hsl(var(--foreground))' }}
                >
                  {/* Chip row — visible only when a skill is @-selected */}
                  {selectedSkill && (
                    <div className="flex items-center gap-1.5 mb-1.5 pb-1.5 border-b" style={{ borderColor: 'hsl(var(--border) / 0.2)' }}>
                      <span className="flex-shrink-0 px-2 py-0.5 rounded-md bg-accent text-xs text-accent-foreground font-medium">
                        @{selectedSkill.name}
                      </span>
                      <button
                        type="button"
                        onClick={() => setSelectedSkill(null)}
                        className="flex-shrink-0 w-4 h-4 rounded-full flex items-center justify-center text-[10px] text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
                        aria-label="移除技能"
                      >
                        ×
                      </button>
                    </div>
                  )}

                  {/* Input row */}
                  <div className="flex items-end gap-1.5">
                    <Popover open={skillPickerOpen} onOpenChange={setSkillPickerOpen}>
                      <PopoverTrigger asChild>
                        <button
                          type="button"
                          className="flex-shrink-0 w-7 h-7 rounded-lg flex items-center justify-center text-sm font-bold text-muted-foreground hover:text-foreground hover:bg-accent transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                          disabled={isRunning}
                          aria-label="选择技能"
                          title={activeSkills.length === 0 ? '没有可用的技能' : '选择技能 (@)'}
                        >
                          @
                        </button>
                      </PopoverTrigger>
                      <PopoverContent side="top" align="start" className="p-0 w-64" sideOffset={8}>
                        <Command>
                          <CommandInput placeholder="搜索技能..." />
                          <CommandList>
                            <CommandEmpty>
                              {availableSkills.length === 0 ? '未安装任何技能' : '无匹配技能'}
                            </CommandEmpty>
                            <CommandGroup heading="已安装技能">
                              {activeSkills.map((skill) => (
                                <CommandItem
                                  key={skill.id}
                                  value={skill.name}
                                  onSelect={() => {
                                    setSelectedSkill({ id: skill.id, name: skill.name });
                                    setSkillPickerOpen(false);
                                  }}
                                >
                                  <span className="text-muted-foreground mr-1">@</span>
                                  <span>{skill.name}</span>
                                  <span className="ml-auto text-[10px] text-muted-foreground">
                                    v{skill.version}
                                  </span>
                                </CommandItem>
                              ))}
                            </CommandGroup>
                          </CommandList>
                        </Command>
                      </PopoverContent>
                    </Popover>

                    <ComposerPrimitive.Input
                      className="flex-1 resize-none bg-transparent text-sm placeholder:text-muted-foreground focus:outline-none py-1"
                      rows={1}
                      placeholder={
                        selectedSkill
                          ? `优化 ${selectedSkill.name}...`
                          : isRunning
                            ? '生成中...'
                            : '描述你想要的应用...'
                      }
                    />
                    <ComposerPrimitive.Send
                      className={`flex-shrink-0 p-2 rounded-lg transition-all ${
                        isRunning
                          ? 'text-muted-foreground cursor-not-allowed'
                          : 'bg-foreground text-background hover:opacity-80'
                      }`}
                    >
                      {isRunning ? (
                        <svg
                          className="animate-spin"
                          width="18"
                          height="18"
                          viewBox="0 0 16 16"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="1.5"
                        >
                          <circle cx="8" cy="8" r="6" strokeOpacity="0.3" />
                          <path d="M14 8a6 6 0 00-10.24-4.24" strokeLinecap="round" />
                        </svg>
                      ) : (
                        <svg
                          width="18"
                          height="18"
                          viewBox="0 0 16 16"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="1.5"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                        >
                          <line x1="8" y1="2" x2="8" y2="14" />
                          <polyline points="4 8 8 14 12 8" />
                        </svg>
                      )}
                    </ComposerPrimitive.Send>
                  </div>
                </div>
              </ComposerPrimitive.Root>
              <p className="text-center mt-1.5 text-[10px] text-muted-foreground">
                {selectedSkill
                  ? `Enter 发送优化指令 · Shift+Enter 换行 · 当前为 @${selectedSkill.name} 优化模式`
                  : 'Enter 发送 · Shift+Enter 换行'}
              </p>
            </div>
          </ThreadPrimitive.Root>
        </AssistantRuntimeProvider>
      </div>

      {/* Rename Dialog */}
      <Dialog open={renameDialogOpen} onOpenChange={setRenameDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>修改对话标题</DialogTitle>
          </DialogHeader>
          <Input
            value={renameTitle}
            onChange={(e) => setRenameTitle(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') confirmRename(); }}
            placeholder="输入新标题"
            maxLength={50}
          />
          <DialogFooter>
            <Button variant="outline" onClick={() => setRenameDialogOpen(false)}>取消</Button>
            <Button onClick={confirmRename} disabled={!renameTitle.trim()}>确定</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete confirmation AlertDialog */}
      <AlertDialog open={deleteConfirm !== null} onOpenChange={(open) => { if (!open) setDeleteConfirm(null); }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              确定要删除对话「{deleteConfirm?.title ?? ''}」吗？此操作不可撤销。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => setDeleteConfirm(null)}>取消</AlertDialogCancel>
            <AlertDialogAction onClick={confirmDelete} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Right-click context menu */}
      {contextMenu && (
        <div
          className="fixed z-50 min-w-[120px] rounded-lg border bg-popover text-popover-foreground shadow-md py-1"
          style={{ left: contextMenu.x, top: contextMenu.y }}
          onClick={(e) => e.stopPropagation()}
        >
          <button
            className="w-full text-left px-3 py-1.5 text-sm hover:bg-accent transition-colors flex items-center gap-2"
            onClick={() => {
              openRenameDialog(contextMenu.convId, contextMenu.title);
              setContextMenu(null);
            }}
          >
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12.146.146a.5.5 0 01.708 0l3 3a.5.5 0 010 .708l-10 10a.5.5 0 01-.168.11l-5 2a.5.5 0 01-.65-.65l2-5a.5.5 0 01.11-.168l10-10z" />
              <path d="M11 1l4 4" />
            </svg>
            重命名
          </button>
          <button
            className="w-full text-left px-3 py-1.5 text-sm hover:bg-accent hover:text-destructive transition-colors flex items-center gap-2"
            onClick={() => {
              setDeleteConfirm({ id: contextMenu.convId, title: contextMenu.title });
              setContextMenu(null);
            }}
          >
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M2 4h12" />
              <path d="M5 4V3a1 1 0 011-1h4a1 1 0 011 1v1" />
              <path d="M13 4v9a1 1 0 01-1 1H4a1 1 0 01-1-1V4" />
            </svg>
            删除
          </button>
        </div>
      )}
    </div>
  );
}
