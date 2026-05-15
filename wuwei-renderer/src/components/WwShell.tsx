import { useState, useEffect, useCallback } from 'react';
import { WwTitleBar } from './WwTitleBar';
import { WwSidebar } from './WwSidebar';
import { WwWorkspace } from './WwWorkspace';
import { WwIntent } from './WwIntent';
import { WwTerminal } from './WwTerminal';
import { WwGateDialog } from './WwGateDialog';
import { WwWorkbench } from './WwWorkbench';
import { WelcomeScreen } from './WelcomeScreen';

export function WwShell() {
  const [connected, setConnected] = useState(false);
  const [kernelVersion, setKernelVersion] = useState('');
  const [activeSkillId, setActiveSkillId] = useState<string | null>(null);

  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const [terminalOpen, setTerminalOpen] = useState(false);

  useEffect(() => {
    const onReady = (e: Event) => {
      const { version } = (e as CustomEvent).detail;
      setConnected(true);
      setKernelVersion(version || '');
    };
    const onSkillActivated = (e: Event) => {
      setActiveSkillId((e as CustomEvent).detail.skillId);
    };
    const onSkillDeactivated = (e: Event) => {
      const { skillId } = (e as CustomEvent).detail;
      setActiveSkillId((prev) => (prev === skillId ? null : prev));
      setDetailOpen(false);
    };
    const onSkillSource = () => {
      setDetailOpen(true);
    };

    window.addEventListener('kernel-ready', onReady);
    window.addEventListener('skill-activated', onSkillActivated);
    window.addEventListener('skill-deactivated', onSkillDeactivated);
    window.addEventListener('skill-source', onSkillSource);

    return () => {
      window.removeEventListener('kernel-ready', onReady);
      window.removeEventListener('skill-activated', onSkillActivated);
      window.removeEventListener('skill-deactivated', onSkillDeactivated);
      window.removeEventListener('skill-source', onSkillSource);
    };
  }, []);

  // Keyboard shortcuts
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key === '`') {
        e.preventDefault();
        setTerminalOpen((v) => !v);
      }
      if (e.ctrlKey && e.key === 'b') {
        e.preventDefault();
        setSidebarCollapsed((v) => !v);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const hasActiveSkill = activeSkillId !== null;

  return (
    <div className="flex flex-col h-screen bg-background">
      {/* Title bar */}
      <WwTitleBar
        connected={connected}
        kernelVersion={kernelVersion}
        terminalOpen={terminalOpen}
        onToggleTerminal={() => setTerminalOpen((v) => !v)}
        detailOpen={detailOpen}
        onToggleDetail={() => setDetailOpen((v) => !v)}
        sidebarCollapsed={sidebarCollapsed}
        onToggleSidebar={() => setSidebarCollapsed((v) => !v)}
      />

      {/* Main layout */}
      <div className="flex flex-1 min-h-0">
        {/* Sidebar */}
        <div
          className="flex-shrink-0 border-r border-sidebar-border bg-sidebar overflow-hidden transition-all duration-200 ease-in-out"
          style={{ width: sidebarCollapsed ? 0 : 260 }}
        >
          <WwSidebar onViewSource={() => setDetailOpen(true)} />
        </div>

        {/* Content area */}
        <div className="flex-1 flex flex-col min-w-0">
          {/* Workspace / Welcome */}
          <div className="flex-1 min-h-0 overflow-auto">
            {hasActiveSkill ? <WwWorkspace /> : <WelcomeScreen />}
          </div>

          {/* Intent bar */}
          <div className="flex-shrink-0 border-t">
            <WwIntent />
          </div>

          {/* Terminal (togglable) */}
          {terminalOpen && (
            <div
              className="flex-shrink-0 border-t animate-slide-up"
              style={{ height: 200 }}
            >
              <WwTerminal />
            </div>
          )}
        </div>

        {/* Detail panel (togglable) */}
        {detailOpen && (
          <div
            className="flex-shrink-0 border-l border-sidebar-border bg-sidebar animate-fade-in overflow-hidden"
            style={{ width: 400 }}
          >
            <WwWorkbench onClose={() => setDetailOpen(false)} />
          </div>
        )}
      </div>

      <WwGateDialog />
    </div>
  );
}
