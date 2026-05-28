import { useState, useEffect, useRef, useCallback } from 'react';
import { useTheme } from '../contexts/ThemeContext';

interface LogEntry {
  timestamp: string;
  level: string;
  skillId: string;
  message: string;
  code?: string;
  type?: string;
}

const ROW_HEIGHT = 26;
const OVERSCAN = 10;

export function WwTerminal() {
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [filter, setFilter] = useState<string | null>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const containerRef = useRef<HTMLDivElement>(null);
  const { resolved: theme } = useTheme();
  const isDark = theme === 'dark';

  useEffect(() => {
    function addLog(entry: Omit<LogEntry, 'timestamp'>) {
      const ts = new Date().toLocaleTimeString('zh-CN', { hour12: false });
      setLogs((prev) => [...prev.slice(-9999), { ...entry, timestamp: ts }]);
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

  const filteredLogs = filter ? logs.filter((l) => l.level === filter) : logs;

  const counts = {
    error: logs.filter((l) => l.level === 'error').length,
    warn: logs.filter((l) => l.level === 'warn').length,
    info: logs.filter((l) => l.level === 'info').length,
  };

  // Virtual scroll
  const totalHeight = filteredLogs.length * ROW_HEIGHT;
  const containerHeight = containerRef.current?.clientHeight ?? 200;

  const handleScroll = useCallback((e: React.UIEvent<HTMLDivElement>) => {
    setScrollTop(e.currentTarget.scrollTop);
  }, []);

  const startIdx = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN);
  const endIdx = Math.min(
    filteredLogs.length,
    Math.ceil((scrollTop + containerHeight) / ROW_HEIGHT) + OVERSCAN,
  );
  const visibleLogs = filteredLogs.slice(startIdx, endIdx);

  // Auto-scroll to bottom when new logs arrive
  const autoScrollRef = useRef(true);
  useEffect(() => {
    if (autoScrollRef.current && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [logs]);

  const onScroll = useCallback(
    (e: React.UIEvent<HTMLDivElement>) => {
      handleScroll(e);
      const el = e.currentTarget;
      autoScrollRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
    },
    [handleScroll],
  );

  const bg = isDark ? 'hsl(var(--terminal-bg))' : 'hsl(var(--background))';
  const fg = isDark ? 'hsl(var(--terminal-fg))' : 'hsl(var(--foreground))';
  const mutedFg = isDark ? 'hsl(0 0% 98% / 0.35)' : 'hsl(var(--muted-foreground))';
  const borderColor = isDark ? 'hsl(0 0% 100% / 0.08)' : 'hsl(var(--border))';
  const hoverBg = isDark ? 'hsl(0 0% 100% / 0.04)' : 'hsl(var(--muted) / 0.5)';
  const errorColor = isDark ? 'hsl(0 72% 58%)' : 'hsl(var(--destructive))';
  const warnColor = isDark ? 'hsl(38 85% 55%)' : 'hsl(30 70% 38%)';
  const infoColor = isDark ? 'hsl(0 0% 98% / 0.5)' : 'hsl(var(--muted-foreground))';

  return (
    <div
      className="flex flex-col h-full"
      style={{ background: bg, color: fg }}
    >
      {/* Toolbar */}
      <div
        className="flex items-center gap-2 px-3 py-1.5 flex-shrink-0"
        style={{ borderBottom: `1px solid ${borderColor}` }}
      >
        <span
          className="text-xs font-semibold select-none"
          style={{ color: mutedFg }}
        >
          终端
        </span>

        <div className="flex items-center gap-1 ml-2">
          {(['error', 'warn', 'info'] as const).map((lvl) => {
            const active = filter === lvl;
            return (
              <button
                key={lvl}
                className={`px-1.5 py-0.5 rounded text-[10px] font-mono transition-colors ${
                  active
                    ? 'bg-primary/15 text-primary'
                    : ''
                }`}
                style={active ? undefined : { color: mutedFg }}
                onClick={() => setFilter(filter === lvl ? null : lvl)}
              >
                {lvl.toUpperCase()}
                {counts[lvl] > 0 && (
                  <span style={{ color: active ? undefined : mutedFg }}>
                    {' '}({counts[lvl]})
                  </span>
                )}
              </button>
            );
          })}
        </div>

        <div className="ml-auto flex items-center gap-1">
          <span className="text-[10px] select-none" style={{ color: mutedFg }}>
            {logs.length} 条
          </span>
          <button
            className="p-1 rounded transition-colors"
            style={{ color: mutedFg }}
            onClick={() => setLogs([])}
            title="清除"
          >
            <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
              <path d="M2 4h12M5.33 4V2.67a.67.67 0 01.67-.67h4a.67.67 0 01.67.67V4" />
              <path d="M3.33 4l.94 9.33c.05.37.37.67.75.67h5.96c.38 0 .7-.3.75-.67L12.67 4" />
            </svg>
          </button>
        </div>
      </div>

      {/* Virtual log list */}
      <div
        ref={containerRef}
        className="flex-1 overflow-auto"
        onScroll={onScroll}
      >
        <div style={{ height: totalHeight, position: 'relative' }}>
          {visibleLogs.map((e, i) => {
            const actualIdx = startIdx + i;
            const levelColor =
              e.level === 'error'
                ? errorColor
                : e.level === 'warn'
                  ? warnColor
                  : infoColor;

            return (
              <div
                key={actualIdx}
                className="flex gap-2 px-3 font-mono text-xs leading-relaxed items-center"
                style={{
                  position: 'absolute',
                  top: actualIdx * ROW_HEIGHT,
                  height: ROW_HEIGHT,
                  left: 0,
                  right: 0,
                  background:
                    e.level === 'error'
                      ? isDark
                        ? 'hsl(0 72% 58% / 0.08)'
                        : 'hsl(0 72% 42% / 0.06)'
                      : e.level === 'warn'
                        ? isDark
                          ? 'hsl(38 85% 55% / 0.06)'
                          : 'hsl(30 70% 38% / 0.05)'
                        : 'transparent',
                }}
                onMouseEnter={(el) => {
                  el.currentTarget.style.background =
                    e.level === 'error'
                      ? isDark
                        ? 'hsl(0 72% 58% / 0.14)'
                        : 'hsl(0 72% 42% / 0.1)'
                      : e.level === 'warn'
                        ? isDark
                          ? 'hsl(38 85% 55% / 0.1)'
                          : 'hsl(30 70% 38% / 0.08)'
                        : hoverBg;
                }}
                onMouseLeave={(el) => {
                  el.currentTarget.style.background =
                    e.level === 'error'
                      ? isDark
                        ? 'hsl(0 72% 58% / 0.08)'
                        : 'hsl(0 72% 42% / 0.06)'
                      : e.level === 'warn'
                        ? isDark
                          ? 'hsl(38 85% 55% / 0.06)'
                          : 'hsl(30 70% 38% / 0.05)'
                        : 'transparent';
                }}
              >
                <span className="flex-shrink-0" style={{ color: mutedFg }}>
                  {e.timestamp}
                </span>
                <span
                  className="font-semibold flex-shrink-0 min-w-[80px] truncate"
                  style={{ color: mutedFg }}
                >
                  {e.skillId}
                </span>
                <span
                  className="flex-shrink-0 min-w-[40px]"
                  style={{ color: levelColor }}
                >
                  {e.level.toUpperCase()}
                </span>
                {e.code && (
                  <span style={{ color: errorColor }}>[{e.code}]</span>
                )}
                {e.type && (
                  <span style={{ color: warnColor }}>[{e.type}]</span>
                )}
                <span className="break-all truncate">{e.message}</span>
              </div>
            );
          })}
          {filteredLogs.length === 0 && (
            <div
              className="px-3 flex items-center"
              style={{
                position: 'absolute',
                top: 0,
                height: ROW_HEIGHT,
                color: mutedFg,
              }}
            >
              <span className="text-xs font-mono">— 等待事件 —</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
