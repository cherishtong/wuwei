import { useState, useEffect } from 'react';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/wv-components/ui/tabs';
import { Button } from '@/wv-components/ui/button';
import { ScrollArea } from '@/wv-components/ui/scroll-area';
import { Badge } from '@/wv-components/ui/badge';
import { Tooltip } from '@/wv-components/ui/tooltip';
import { kernel } from '../kernel';

interface SkillMeta {
  id: string;
  name: string;
  status: string;
  version: string;
}

interface ActivityEntry {
  id: string;
  skillId: string;
  type: string;
  message: string;
  time: string;
}

interface WwSidebarProps {
  onViewSource: () => void;
}

export function WwSidebar({ onViewSource }: WwSidebarProps) {
  const [skills, setSkills] = useState<SkillMeta[]>([]);
  const [activeSkillId, setActiveSkillId] = useState<string | null>(null);
  const [activities, setActivities] = useState<ActivityEntry[]>([]);
  const [tab, setTab] = useState('skills');

  useEffect(() => {
    const onSkillList = (e: Event) => setSkills((e as CustomEvent).detail.skills || []);
    const onSkillActivated = (e: Event) => {
      const { skillId } = (e as CustomEvent).detail;
      setActiveSkillId(skillId);
      setSkills((s) => s.map((x) => (x.id === skillId ? { ...x, status: 'running' } : x)));
      addActivity(skillId, 'activated', 'Skill 已激活');
    };
    const onSkillDeactivated = (e: Event) => {
      const { skillId } = (e as CustomEvent).detail;
      if (activeSkillId === skillId) setActiveSkillId(null);
      setSkills((s) => s.map((x) => (x.id === skillId ? { ...x, status: 'stopped' } : x)));
      addActivity(skillId, 'deactivated', 'Skill 已停用');
    };
    const onSkillLoading = (e: Event) => {
      const { skillId } = (e as CustomEvent).detail;
      setSkills((s) => {
        if (s.find((x) => x.id === skillId)) return s;
        return [...s, { id: skillId, name: skillId, status: 'loading', version: '...' }];
      });
      addActivity(skillId, 'loading', '正在加载...');
    };
    const onPlanStep = (e: Event) => {
      const d = (e as CustomEvent).detail;
      addActivity('system', d.status, d.desc);
    };

    window.addEventListener('skill-list', onSkillList);
    window.addEventListener('skill-activated', onSkillActivated);
    window.addEventListener('skill-deactivated', onSkillDeactivated);
    window.addEventListener('skill-loading', onSkillLoading);
    window.addEventListener('plan-step', onPlanStep);

    return () => {
      window.removeEventListener('skill-list', onSkillList);
      window.removeEventListener('skill-activated', onSkillActivated);
      window.removeEventListener('skill-deactivated', onSkillDeactivated);
      window.removeEventListener('skill-loading', onSkillLoading);
      window.removeEventListener('plan-step', onPlanStep);
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function addActivity(skillId: string, type: string, message: string) {
    const now = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    setActivities((prev) => [
      { id: `${Date.now()}-${Math.random().toString(36).slice(2, 6)}`, skillId, type, message, time: now },
      ...prev.slice(0, 49),
    ]);
  }

  async function onInstall() {
    if ((window as any).__TAURI_INTERNALS__) {
      try {
        const { invoke } = await import('@tauri-apps/api/core');
        const selected = await invoke<string>('pick_folder');
        if (selected && selected.length > 0) {
          kernel.send({ type: 'install-skill', path: selected });
        }
        return;
      } catch { /* fall through */ }
    }
    const path = prompt('请输入 Skill 文件夹的绝对路径:');
    if (path) kernel.send({ type: 'install-skill', path });
  }

  function handleViewSource(skillId: string) {
    kernel.send({ type: 'get-skill-source', skillId });
    onViewSource();
  }

  function onUninstall(skillId: string) {
    if (confirm(`确定要卸载 Skill "${skillId}" 吗？`)) {
      kernel.send({ type: 'uninstall-skill', skillId });
    }
  }

  function onSkillClick(skillId: string) {
    if (skillId === activeSkillId) {
      kernel.deactivateSkill(skillId);
    } else {
      kernel.activateSkill(skillId);
    }
  }

  const statusDot = (status: string) => {
    const map: Record<string, string> = {
      running: 'bg-green-500',
      error: 'bg-red-500',
      loading: 'bg-yellow-500 animate-pulse',
    };
    return map[status] || 'bg-gray-400';
  };

  const statusLabel = (status: string) => {
    const map: Record<string, string> = {
      running: '运行中',
      error: '错误',
      loading: '加载中',
      stopped: '已停止',
    };
    return map[status] || status;
  };

  return (
    <div className="flex flex-col h-full">
      {/* Tabs */}
      <div className="px-3 pt-3">
        <Tabs value={tab} onValueChange={setTab}>
          <TabsList className="w-full">
            <TabsTrigger value="skills" className="flex-1 text-xs">
              Skills
            </TabsTrigger>
            <TabsTrigger value="activity" className="flex-1 text-xs">
              动态
            </TabsTrigger>
          </TabsList>
        </Tabs>
      </div>

      {/* Skills panel */}
      {tab === 'skills' && (
        <div className="flex flex-col flex-1 min-h-0 p-2 gap-1">
          <ScrollArea className="flex-1">
            {skills.length === 0 ? (
              <div className="p-6 text-center text-sm text-muted-foreground">
                <p className="mb-2">暂无已安装的 Skill</p>
                <p className="text-xs">点击下方按钮安装一个 Skill 开始使用</p>
              </div>
            ) : (
              skills.map((s) => (
                <div
                  key={s.id}
                  className={`group flex items-center gap-2.5 px-2.5 py-2 rounded-md cursor-pointer text-sm transition-all duration-150 ${
                    s.id === activeSkillId
                      ? 'bg-sidebar-active text-foreground ring-1 ring-border'
                      : 'hover:bg-sidebar-hover text-foreground'
                  }`}
                  onClick={() => onSkillClick(s.id)}
                >
                  {/* Status dot */}
                  <Tooltip content={statusLabel(s.status)}>
                    <span
                      className={`w-2 h-2 rounded-full flex-shrink-0 ${statusDot(s.status)}`}
                    />
                  </Tooltip>

                  {/* Skill info */}
                  <span className="flex-1 truncate font-medium">{s.name}</span>
                  <span className="text-[10px] text-muted-foreground flex-shrink-0">
                    v{s.version}
                  </span>

                  {/* Hover actions */}
                  <span className="hidden group-hover:flex items-center gap-0.5 flex-shrink-0">
                    <Tooltip content="查看源码">
                      <button
                        className="p-1 rounded hover:bg-accent transition-colors text-muted-foreground hover:text-foreground"
                        onClick={(e) => {
                          e.stopPropagation();
                          handleViewSource(s.id);
                        }}
                      >
                        <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                          <path d="M2 6l4-4 8 8-4 4-8-8z" />
                          <circle cx="5" cy="11" r="1.5" />
                        </svg>
                      </button>
                    </Tooltip>
                    <Tooltip content="卸载">
                      <button
                        className="p-1 rounded hover:bg-destructive/10 transition-colors text-muted-foreground hover:text-destructive"
                        onClick={(e) => {
                          e.stopPropagation();
                          onUninstall(s.id);
                        }}
                      >
                        <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                          <path d="M2 4h12M5.33 4V2.67a.67.67 0 01.67-.67h4a.67.67 0 01.67.67V4M6.67 7v5M9.33 7v5" />
                          <path d="M3.33 4l.94 9.33c.05.37.37.67.75.67h5.96c.38 0 .7-.3.75-.67L12.67 4" />
                        </svg>
                      </button>
                    </Tooltip>
                  </span>

                  {/* Click hint */}
                  <span className="hidden group-hover:inline-flex text-[10px] text-muted-foreground">
                    {s.id === activeSkillId ? '停用' : '激活'}
                  </span>
                </div>
              ))
            )}
          </ScrollArea>

          <div className="flex-shrink-0 pt-1">
            <Button variant="outline" size="sm" className="w-full text-xs" onClick={onInstall}>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" className="mr-1.5">
                <line x1="8" y1="2" x2="8" y2="14" />
                <line x1="2" y1="8" x2="14" y2="8" />
              </svg>
              安装 Skill
            </Button>
          </div>
        </div>
      )}

      {/* Activity panel */}
      {tab === 'activity' && (
        <div className="flex-1 min-h-0">
          <ScrollArea className="h-full">
            {activities.length === 0 ? (
              <div className="p-6 text-center text-sm text-muted-foreground">
                暂无动态
              </div>
            ) : (
              <div className="px-3 py-2 space-y-1">
                {activities.map((a) => (
                  <div key={a.id} className="flex gap-2 py-1.5 text-xs">
                    <span className="text-muted-foreground flex-shrink-0 w-10 text-right">
                      {a.time}
                    </span>
                    <span className="font-medium flex-shrink-0 text-muted-foreground">
                      {a.skillId}
                    </span>
                    <span className="text-muted-foreground truncate">{a.message}</span>
                  </div>
                ))}
              </div>
            )}
          </ScrollArea>
        </div>
      )}

    </div>
  );
}
