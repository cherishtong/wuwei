import { useState, useEffect, useCallback, useRef } from 'react';
import { Group, Panel, Separator } from 'react-resizable-panels';
import { WwTitleBar } from './WwTitleBar';
import { WwNavbar, type NavTab } from './WwNavbar';
import { WwIntent } from './WwIntent';
import { LogViewer } from './LogViewer';
import { WwGateDialog } from './WwGateDialog';
import { WwModelConfig } from './WwModelConfig';
import { SkillsPage } from './SkillsPage';
import { SystemPage } from './SystemPage';
import { WuweiChat } from './WuweiChat';
import { SkillPanel } from './SkillPanel';
import { SystemMonitor } from './SystemMonitor';
import { WwLoading } from './WwLoading';
import { WwFloatingMenu } from './WwFloatingMenu';
import { conversationStore } from '../stores/ConversationStore';
import { kernel } from '../kernel';

export function WwShell() {
  const [connected, setConnected] = useState(false);
  const [kernelVersion, setKernelVersion] = useState('');
  const [skillCount, setSkillCount] = useState(0);
  const [activeSkillId, setActiveSkillId] = useState<string | null>(null);
  const [activeSkillName, setActiveSkillName] = useState<string>('');
  const [globalSkillDetail, setGlobalSkillDetail] = useState<Record<string, unknown> | null>(null);
  const [activeTab, setActiveTab] = useState<NavTab>('home');
  const [terminalOpen, setTerminalOpen] = useState(false);
  const [modelConfigOpen, setModelConfigOpen] = useState(false);
  const [activeThreadId, setActiveThreadId] = useState<string | null>(null);
  const activeThreadIdRef = useRef<string | null>(null);
  activeThreadIdRef.current = activeThreadId;
  const [skillPanelVisible, setSkillPanelVisible] = useState(false);
  const [, forceRender] = useState(0);

  useEffect(() => {
    const onReady = (e: Event) => {
      const { version } = (e as CustomEvent).detail;
      setConnected(true);
      setKernelVersion(version || '');
    };
    const onSkillActivated = (e: Event) => {
      const d = (e as CustomEvent).detail;
      setActiveSkillId(d.skillId);
      setActiveSkillName(d.skillName ?? d.skillId);
      setGlobalSkillDetail(d);
      setSkillPanelVisible(true);
    };
    const onSkillDeactivated = (e: Event) => {
      const { skillId } = (e as CustomEvent).detail;
      setActiveSkillId((prev) => (prev === skillId ? null : prev));
      setActiveSkillName('');
      setGlobalSkillDetail(null);
    };
    const onSkillList = (e: Event) => {
      const skills = (e as CustomEvent).detail.skills || [];
      setSkillCount(skills.length);
    };

    window.addEventListener('kernel-ready', onReady);
    window.addEventListener('skill-activated', onSkillActivated);
    window.addEventListener('skill-deactivated', onSkillDeactivated);
    window.addEventListener('skill-list', onSkillList);

    return () => {
      window.removeEventListener('kernel-ready', onReady);
      window.removeEventListener('skill-activated', onSkillActivated);
      window.removeEventListener('skill-deactivated', onSkillDeactivated);
      window.removeEventListener('skill-list', onSkillList);
    };
  }, []);

  // Initialize thread
  useEffect(() => {
    conversationStore.ensureReady().then(() => {
      const list = conversationStore.list();
      if (list.length > 0) {
        setActiveThreadId(list[0].id);
      } else {
        conversationStore.findOrCreate().then((c) => setActiveThreadId(c.id));
      }
    });
  }, []);

  // Listen for conversation store changes
  useEffect(() => {
    return conversationStore.onChange(() => {
      forceRender((n) => n + 1);
    });
  }, []);

  // Derive skill info from active thread
  const activeConv = activeThreadId ? conversationStore.get(activeThreadId) : null;
  const threadSkillId = activeConv?.activeSkillId ?? null;
  const effectiveSkillId = threadSkillId ?? activeSkillId;
  const effectiveDetail = globalSkillDetail;

  const onDeactivateSkill = useCallback(() => {
    if (effectiveSkillId && activeThreadId) {
      conversationStore.updateActiveSkill(activeThreadId, null);
      kernel.deactivateSkill(effectiveSkillId, activeThreadId);
    }
  }, [effectiveSkillId, activeThreadId]);


  // Keyboard shortcuts
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key === '`') {
        e.preventDefault();
        setTerminalOpen((v) => !v);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  // "使用技能" card button → show skill panel or activate skill
  useEffect(() => {
    const handler = (e: Event) => {
      const { skillId } = (e as CustomEvent).detail;
      var tid = activeThreadIdRef.current;
      if (skillId && tid) {
        if (effectiveSkillId !== skillId) {
          kernel.activateSkill(skillId, tid);
        }
        setSkillPanelVisible(true);
      }
    };
    window.addEventListener('view-active-skill', handler);
    return () => window.removeEventListener('view-active-skill', handler);
  }, [activeThreadId, effectiveSkillId]);

  if (!connected) {
    return <WwLoading />;
  }

  return (
    <div className="flex flex-col h-screen bg-background">
      {/* Title bar: logo + theme + window controls */}
      <WwTitleBar connected={connected} kernelVersion={kernelVersion} />

      {/* Navbar: 首页 / 技能 / 系统 */}
      <WwNavbar activeTab={activeTab} onTabChange={(tab) => { setActiveTab(tab); }} />

      {/* Content */}
      <div className="flex-1 flex flex-col min-h-0">
        <div className="flex-1 min-h-0 flex flex-col">
          {activeTab === 'home' && activeThreadId && (
            <Group
              orientation="horizontal"
              className="flex-1 min-h-0"
            >
              <Panel defaultSize={skillPanelVisible && effectiveSkillId ? 60 : 100} minSize={30}>
                <WuweiChat threadId={activeThreadId} onThreadChange={setActiveThreadId} />
              </Panel>

              {skillPanelVisible && effectiveSkillId && (
                <>
                  <Separator
                    style={{
                      background: 'hsl(var(--border))',
                      width: 4,
                      cursor: 'col-resize',
                    }}
                  />
                  <Panel defaultSize={40} minSize={20}>
                    <div style={{ height: '100%', overflow: 'auto' }}>
                      <SkillPanel
                        skillId={effectiveSkillId}
                        activeThreadId={activeThreadId}
                        initDetail={effectiveDetail}
                        onDeactivate={onDeactivateSkill}
                      />
                    </div>
                  </Panel>
                </>
              )}
            </Group>
          )}
          {activeTab === 'skills' && (
            <SkillsPage
              activeSkillId={activeSkillId}
              initDetail={globalSkillDetail}
              onOpenModelConfig={() => setModelConfigOpen(true)}
            />
          )}
          {activeTab === 'system' && (
            <SystemPage onOpenModelConfig={() => setModelConfigOpen(true)} />
          )}
          {activeTab === 'monitor' && (
            <div className="flex-1 min-h-0 overflow-auto">
              <SystemMonitor />
            </div>
          )}
        </div>

        {/* Intent bar — home has inline chat, skills/system don't need it */}
        {activeTab !== 'home' && activeTab !== 'skills' && activeTab !== 'system' && activeTab !== 'monitor' && (
          <div className="flex-shrink-0 border-t">
            <WwIntent />
          </div>
        )}

        {/* Terminal / Log Viewer — draggable height */}
        {terminalOpen && (
          <div className="flex-shrink-0 border-t animate-slide-up relative" style={{ height: 300, minHeight: 100, maxHeight: '80vh' }}
            ref={el => {
              if (!el) return;
              let startY = 0, startH = 0;
              const handle = el.querySelector('.log-drag-handle') as HTMLElement;
              if (!handle) return;
              handle.onmousedown = (e) => {
                startY = e.clientY; startH = el.offsetHeight;
                document.body.style.cursor = 'ns-resize'; document.body.style.userSelect = 'none';
                const onMove = (ev: MouseEvent) => { el.style.height = Math.max(100, Math.min(window.innerHeight*0.8, startH + startY - ev.clientY)) + 'px'; };
                const onUp = () => { document.body.style.cursor = ''; document.body.style.userSelect = ''; document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp); };
                document.addEventListener('mousemove', onMove); document.addEventListener('mouseup', onUp);
              };
            }}>
            <div className="log-drag-handle absolute top-0 left-0 right-0 h-1 cursor-ns-resize hover:bg-primary/30 z-10" />
            <LogViewer />
          </div>
        )}
      </div>

      <WwGateDialog />
      <WwModelConfig open={modelConfigOpen} onClose={() => setModelConfigOpen(false)} />

      {/* Floating action menu */}
      <WwFloatingMenu
        onInstall={() => {
          setActiveTab('skills');
          window.dispatchEvent(new CustomEvent('floating-install'));
        }}
        onToggleConsole={() => setTerminalOpen((v) => !v)}
        onOpenModelConfig={() => setModelConfigOpen(true)}
      />
    </div>
  );
}
