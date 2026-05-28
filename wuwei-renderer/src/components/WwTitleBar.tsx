import { useState, useEffect, useRef, useCallback } from 'react';
import { useTheme } from '../contexts/ThemeContext';
import { Tooltip } from '@/wv-components/ui/tooltip';
import logo from '../assets/logo.png';

interface TitleBarProps {
  connected: boolean;
  kernelVersion: string;
}

export function WwTitleBar({ connected, kernelVersion }: TitleBarProps) {
  const { resolved, toggle } = useTheme();
  const [isTauri, setIsTauri] = useState(false);
  const [isMaximized, setIsMaximized] = useState(false);
  const tauriWindowRef = useRef<any>(null);

  useEffect(() => {
    let cancelled = false;
    async function init() {
      try {
        const { getCurrentWindow } = await import('@tauri-apps/api/window');
        const win = getCurrentWindow();
        if (cancelled) return;
        tauriWindowRef.current = win;
        setIsTauri(true);
        const m = await win.isMaximized();
        if (!cancelled) setIsMaximized(m);
        await win.onResized(async () => {
          if (cancelled) return;
          try { setIsMaximized(await win.isMaximized()); } catch { /* ignore */ }
        });
      } catch { /* not Tauri */ }
    }
    init();
    return () => { cancelled = true; };
  }, []);

  const minimizeWindow = useCallback(() => tauriWindowRef.current?.minimize(), []);
  const toggleMaximize = useCallback(() => tauriWindowRef.current?.toggleMaximize(), []);
  const closeWindow = useCallback(() => tauriWindowRef.current?.close(), []);

  return (
    <div
      className="flex items-center h-titlebar flex-shrink-0 select-none border-b z-30"
      style={{
        height: 'var(--titlebar-height)',
        background: resolved === 'dark' ? 'rgba(9,9,11,0.88)' : 'rgba(249,246,241,0.72)',
        backdropFilter: 'blur(20px)',
        WebkitBackdropFilter: 'blur(20px)',
        borderColor: resolved === 'dark' ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.06)',
      }}
    >
      {/* Left: logo + drag region */}
      <div
        data-tauri-drag-region={isTauri ? '' : undefined}
        className="flex items-center gap-3 pl-3 h-full flex-1"
      >
        <img src={logo} alt="Wuwei" className="w-7 h-7 rounded-lg flex-shrink-0 object-cover" />
        <span
          className="text-sm font-bold tracking-[0.12em]"
          style={{
            fontFamily: '"Noto Serif SC", "Songti SC", serif',
            color: resolved === 'dark' ? '#fff' : '#1a1a1b',
          }}
        >
          无为
        </span>
        <Tooltip content={connected ? `Kernel ${kernelVersion}` : '未连接'}>
          <span
            className="w-1.5 h-1.5 rounded-full ml-1"
            style={{
              backgroundColor: connected ? '#22c55e' : '#9ca3af',
              animation: connected ? 'pulse-dot 2s infinite' : 'none',
            }}
          />
        </Tooltip>
      </div>

      {/* Right: theme + window controls */}
      <div className="flex items-center gap-1 pr-1 titlebar-no-drag">
        <Tooltip content={resolved === 'dark' ? '浅色模式' : '深色模式'}>
          <button
            className="p-1.5 rounded-md hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
            onClick={toggle}
          >
            {resolved === 'dark' ? (
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                <circle cx="8" cy="8" r="3" />
                <path d="M8 1v1M8 14v1M1 8h1M14 8h1M3.05 3.05l.7.7M12.25 12.25l.7.7M3.05 12.95l.7-.7M12.25 3.75l.7-.7" />
              </svg>
            ) : (
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                <path d="M13.5 10.5A5.5 5.5 0 015.5 2.5 5.5 5.5 0 1013.5 10.5z" />
              </svg>
            )}
          </button>
        </Tooltip>

        {isTauri && (
          <>
            <div className="w-px h-4 bg-border mx-0.5" />
            <button
              className="p-1.5 rounded-md hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
              onClick={minimizeWindow}
            >
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                <line x1="3" y1="8" x2="13" y2="8" />
              </svg>
            </button>
            <button
              className="p-1.5 rounded-md hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
              onClick={toggleMaximize}
            >
              {isMaximized ? (
                <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="3" y="5" width="8" height="6" rx="1" />
                  <path d="M6 5V4a1 1 0 011-1h5a1 1 0 011 1v5a1 1 0 01-1 1h-1" />
                </svg>
              ) : (
                <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                  <rect x="3" y="3" width="10" height="10" rx="1" />
                </svg>
              )}
            </button>
            <button
              className="p-1.5 rounded-md hover:bg-destructive hover:text-destructive-foreground text-muted-foreground transition-colors"
              onClick={closeWindow}
            >
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                <line x1="4" y1="4" x2="12" y2="12" />
                <line x1="12" y1="4" x2="4" y2="12" />
              </svg>
            </button>
          </>
        )}
      </div>
    </div>
  );
}
