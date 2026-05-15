import { useState, useEffect, useRef, useCallback } from 'react';
import { useTheme } from '../contexts/ThemeContext';
import { Tooltip } from '@/wv-components/ui/tooltip';
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuLabel,
} from '@/wv-components/ui/dropdown-menu';
import { kernel } from '../kernel';

interface TitleBarProps {
  connected: boolean;
  kernelVersion: string;
  terminalOpen: boolean;
  onToggleTerminal: () => void;
  detailOpen: boolean;
  onToggleDetail: () => void;
  sidebarCollapsed: boolean;
  onToggleSidebar: () => void;
}

export function WwTitleBar({
  connected,
  kernelVersion,
  terminalOpen,
  onToggleTerminal,
  sidebarCollapsed,
  onToggleSidebar,
}: TitleBarProps) {
  const { resolved, toggle } = useTheme();
  const [isTauri, setIsTauri] = useState(false);
  const [isMaximized, setIsMaximized] = useState(false);
  const tauriWindowRef = useRef<any>(null);

  // Initialize Tauri window API once
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
          try {
            setIsMaximized(await win.isMaximized());
          } catch { /* ignore */ }
        });
      } catch {
        // Not in Tauri environment
      }
    }

    init();
    return () => { cancelled = true; };
  }, []);

  const minimizeWindow = useCallback(() => {
    tauriWindowRef.current?.minimize();
  }, []);

  const toggleMaximize = useCallback(() => {
    tauriWindowRef.current?.toggleMaximize();
  }, []);

  const closeWindow = useCallback(() => {
    tauriWindowRef.current?.close();
  }, []);

  return (
    <div
      className="flex items-center h-titlebar bg-titlebar border-b border-sidebar-border select-none flex-shrink-0"
      style={{ height: 'var(--titlebar-height)' }}
    >
      {/* Left: drag region */}
      <div
        data-tauri-drag-region={isTauri ? '' : undefined}
        className={`flex items-center gap-2 pl-3 h-full flex-1`}
      >
        <span className="text-sm font-semibold tracking-tight text-foreground">
          无为 <span className="text-muted-foreground font-normal">Wuwei</span>
        </span>
      </div>

      {/* Right: actions (no-drag so buttons are clickable) */}
      <div className="flex items-center gap-1 pr-1">
        {/* Connection status */}
        <Tooltip content={connected ? `Kernel ${kernelVersion}` : '未连接'}>
          <span className="flex items-center gap-1.5 px-2 py-1 text-xs text-muted-foreground">
            <span
              className={`w-1.5 h-1.5 rounded-full ${
                connected ? 'bg-green-500' : 'bg-gray-400'
              }`}
              style={connected ? { animation: 'pulse-dot 2s infinite' } : {}}
            />
            {connected ? '已连接' : '未连接'}
          </span>
        </Tooltip>

        {/* Sidebar toggle */}
        <Tooltip content={sidebarCollapsed ? '显示侧边栏' : '隐藏侧边栏'}>
          <button
            className="p-1.5 rounded-md hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
            onClick={onToggleSidebar}
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
              <rect x="1" y="2" width="14" height="12" rx="1" />
              <line x1="5" y1="2" x2="5" y2="14" />
            </svg>
          </button>
        </Tooltip>

        {/* Terminal toggle */}
        <Tooltip content={terminalOpen ? '隐藏终端 (Ctrl+`)' : '显示终端 (Ctrl+`)'}>
          <button
            className={`p-1.5 rounded-md transition-colors ${
              terminalOpen
                ? 'bg-accent text-foreground'
                : 'hover:bg-accent text-muted-foreground hover:text-foreground'
            }`}
            onClick={onToggleTerminal}
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M2 4l3 3-3 3M7 11h4" />
              <rect x="1" y="1" width="14" height="14" rx="2" />
            </svg>
          </button>
        </Tooltip>

        {/* Separator */}
        {isTauri && <div className="w-px h-4 bg-border mx-0.5" />}

        {/* Window controls (Tauri only) */}
        {isTauri && (
          <>
            <button
              className="p-1.5 rounded-md hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
              onClick={minimizeWindow}
            >
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                <line x1="3" y1="8" x2="13" y2="8" />
              </svg>
            </button>

            <button
              className="p-1.5 rounded-md hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
              onClick={toggleMaximize}
            >
              {isMaximized ? (
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="3" y="5" width="8" height="6" rx="1" />
                  <path d="M6 5V4a1 1 0 011-1h5a1 1 0 011 1v5a1 1 0 01-1 1h-1" />
                </svg>
              ) : (
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                  <rect x="3" y="3" width="10" height="10" rx="1" />
                </svg>
              )}
            </button>

            <button
              className="p-1.5 rounded-md hover:bg-destructive hover:text-destructive-foreground text-muted-foreground transition-colors"
              onClick={closeWindow}
            >
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                <line x1="4" y1="4" x2="12" y2="12" />
                <line x1="12" y1="4" x2="4" y2="12" />
              </svg>
            </button>
          </>
        )}

        {/* Divider + theme toggle */}
        <div className="w-px h-4 bg-border mx-0.5" />
        <Tooltip content={resolved === 'dark' ? '切换到浅色模式' : '切换到深色模式'}>
          <button
            className="p-1.5 rounded-md hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
            onClick={toggle}
          >
            {resolved === 'dark' ? (
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                <circle cx="8" cy="8" r="3" />
                <path d="M8 1v1M8 14v1M1 8h1M14 8h1M3.05 3.05l.7.7M12.25 12.25l.7.7M3.05 12.95l.7-.7M12.25 3.75l.7-.7" />
              </svg>
            ) : (
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                <path d="M13.5 10.5A5.5 5.5 0 015.5 2.5 5.5 5.5 0 1013.5 10.5z" />
              </svg>
            )}
          </button>
        </Tooltip>

        {/* Settings menu */}
        <DropdownMenu>
          <Tooltip content="设置">
            <DropdownMenuTrigger asChild>
              <button className="p-1.5 rounded-md hover:bg-accent text-muted-foreground hover:text-foreground transition-colors">
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                  <circle cx="8" cy="8" r="2.5" />
                  <path d="M8 1v2M8 13v2M1 8h2M13 8h2M3.05 3.05l1.41 1.41M11.54 11.54l1.41 1.41M3.05 12.95l1.41-1.41M11.54 4.46l1.41-1.41" />
                </svg>
              </button>
            </DropdownMenuTrigger>
          </Tooltip>
          <DropdownMenuContent align="end" className="w-48">
            <DropdownMenuLabel>设置</DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={toggle}>
              {resolved === 'dark' ? '☀️ 浅色模式' : '🌙 深色模式'}
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => kernel.send({ type: 'list-skills' })}>
              🔄 刷新 Skill 列表
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => kernel.send({ type: 'get-settings' })}>
              ⚙️ 内核设置
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  );
}
