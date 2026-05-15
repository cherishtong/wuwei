import { useState, useEffect, useRef } from 'react';
import { Button } from '@/wv-components/ui/button';
import { ScrollArea } from '@/wv-components/ui/scroll-area';
import { Tooltip } from '@/wv-components/ui/tooltip';

interface LogEntry {
  timestamp: string;
  level: string;
  skillId: string;
  message: string;
  code?: string;
  type?: string;
}

const levelColors: Record<string, string> = {
  error: 'text-red-400',
  warn: 'text-yellow-400',
  info: 'text-blue-400',
};

const levelBg: Record<string, string> = {
  error: 'bg-red-500/10',
  warn: 'bg-yellow-500/10',
  info: 'bg-transparent',
};

export function WwTerminal() {
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [filter, setFilter] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function addLog(entry: Omit<LogEntry, 'timestamp'>) {
      const ts = new Date().toLocaleTimeString('zh-CN', { hour12: false });
      setLogs((prev) => [...prev.slice(-199), { ...entry, timestamp: ts }]);
    }

    const onSkillLog = (e: Event) => {
      const d = (e as CustomEvent).detail;
      addLog({ level: d.level || 'info', skillId: d.skillId, message: d.message });
    };
    const onKernelError = (e: Event) => {
      const d = (e as CustomEvent).detail;
      addLog({ level: 'error', skillId: d.skillId, message: d.message, code: d.code });
    };
    const onGuardianWarn = (e: Event) => {
      const d = (e as CustomEvent).detail;
      addLog({ level: 'warn', skillId: d.skillId, message: d.message, type: d.type });
    };
    const onRepair = (e: Event) => {
      const d = (e as CustomEvent).detail;
      addLog({ level: 'warn', skillId: d.skillId, message: `Repair attempt ${d.attempt}: ${d.error}` });
    };
    const onPlanStep = (e: Event) => {
      const d = (e as CustomEvent).detail;
      addLog({ level: 'info', skillId: 'system', message: `[${d.status}] ${d.desc}` });
    };

    window.addEventListener('skill-log', onSkillLog);
    window.addEventListener('kernel-error', onKernelError);
    window.addEventListener('guardian-warning', onGuardianWarn);
    window.addEventListener('repair-attempt', onRepair);
    window.addEventListener('plan-step', onPlanStep);

    return () => {
      window.removeEventListener('skill-log', onSkillLog);
      window.removeEventListener('kernel-error', onKernelError);
      window.removeEventListener('guardian-warning', onGuardianWarn);
      window.removeEventListener('repair-attempt', onRepair);
      window.removeEventListener('plan-step', onPlanStep);
    };
  }, []);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [logs]);

  const filteredLogs = filter ? logs.filter((l) => l.level === filter) : logs;

  const counts = {
    error: logs.filter((l) => l.level === 'error').length,
    warn: logs.filter((l) => l.level === 'warn').length,
    info: logs.filter((l) => l.level === 'info').length,
  };

  return (
    <div className="flex flex-col h-full bg-[hsl(var(--terminal-bg))] text-[hsl(var(--terminal-fg))]">
      {/* Toolbar */}
      <div className="flex items-center gap-2 px-3 py-1.5 border-b border-white/10 flex-shrink-0">
        <span className="text-xs font-semibold text-white/60">终端</span>

        <div className="flex items-center gap-1 ml-2">
          {(['error', 'warn', 'info'] as const).map((lvl) => (
            <button
              key={lvl}
              className={`px-1.5 py-0.5 rounded text-[10px] font-mono transition-colors ${
                filter === lvl
                  ? 'bg-white/15 text-white'
                  : 'text-white/40 hover:text-white/70'
              }`}
              onClick={() => setFilter(filter === lvl ? null : lvl)}
            >
              {lvl.toUpperCase()} {counts[lvl] > 0 && `(${counts[lvl]})`}
            </button>
          ))}
        </div>

        <div className="ml-auto flex items-center gap-1">
          <span className="text-[10px] text-white/30">{logs.length} 条</span>
          <Tooltip content="清除">
            <button
              className="p-1 rounded text-white/40 hover:text-white/70 transition-colors"
              onClick={() => setLogs([])}
            >
              <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                <path d="M2 4h12M5.33 4V2.67a.67.67 0 01.67-.67h4a.67.67 0 01.67.67V4" />
                <path d="M3.33 4l.94 9.33c.05.37.37.67.75.67h5.96c.38 0 .7-.3.75-.67L12.67 4" />
              </svg>
            </button>
          </Tooltip>
        </div>
      </div>

      {/* Log lines */}
      <ScrollArea className="flex-1" ref={scrollRef}>
        <div className="py-1 font-mono text-xs leading-relaxed">
          {filteredLogs.length === 0 ? (
            <p className="px-3 py-2 text-white/30">— 等待事件 —</p>
          ) : (
            filteredLogs.map((e, i) => (
              <div
                key={i}
                className={`flex gap-2 px-3 py-px hover:bg-white/5 ${levelBg[e.level] || ''}`}
              >
                <span className="text-white/30 flex-shrink-0">{e.timestamp}</span>
                <span className="font-semibold text-white/60 flex-shrink-0 min-w-[80px]">
                  {e.skillId}
                </span>
                <span className={`flex-shrink-0 min-w-[40px] ${levelColors[e.level] || ''}`}>
                  {e.level.toUpperCase()}
                </span>
                {e.code && <span className="text-red-400">[{e.code}]</span>}
                {e.type && <span className="text-yellow-400">[{e.type}]</span>}
                <span className="break-all text-white/80">{e.message}</span>
              </div>
            ))
          )}
        </div>
      </ScrollArea>
    </div>
  );
}
