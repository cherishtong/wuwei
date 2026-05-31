import { useState, useEffect } from 'react';
import { Button } from '@/wv-components/ui/button';
import { ScrollArea } from '@/wv-components/ui/scroll-area';

import { Switch } from '@/wv-components/ui/switch';
import { WwWorkspace } from './WwWorkspace';
import { SkillPanel } from './SkillPanel';
import { WwResizableLayout } from './WwResizableLayout';
import { useTheme } from '../contexts/ThemeContext';
import { kernel } from '../kernel';

type ViewMode = 'list' | 'card';

const SPINE_COLORS = [
  'hsl(8,62%,44%)',
  'hsl(18,56%,46%)',
  'hsl(28,48%,48%)',
  'hsl(14,50%,42%)',
  'hsl(22,58%,43%)',
  'hsl(10,52%,45%)',
  'hsl(26,44%,44%)',
  'hsl(16,56%,40%)',
];

function hashId(id: string): number {
  let hash = 0;
  for (let i = 0; i < id.length; i++) {
    hash = id.charCodeAt(i) + ((hash << 5) - hash);
  }
  return Math.abs(hash);
}

function spineColor(id: string): string {
  return SPINE_COLORS[hashId(id) % SPINE_COLORS.length];
}

const ICON_PATHS = [
  // 书卷
  'M4 4h5l3 4h6v10a2 2 0 01-2 2H4a2 2 0 01-2-2V6a2 2 0 012-2z',
  // 云纹
  'M7 16a4 4 0 01-.37-7.98A6.01 6.01 0 0118 9.2 3.5 3.5 0 0119 16H7z',
  // 棋盘
  'M3 3h6v6H3zM11 3h6v6h-6zM3 11h6v6H3zM11 11h6v6h-6z',
  // 星斗
  'M12 2l2.5 6.5L21 9l-4.5 3.5L18 19l-6-4-6 4 1.5-6.5L3 9l6.5-.5z',
  // 正十七
  'M12 2l8 4.5L18 20H6L4 6.5z',
  // 日轮
  'M12 7a5 5 0 100 10 5 5 0 000-10zM12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42',
  // 篆刻
  'M9 2h6l3 3v14a2 2 0 01-2 2H8a2 2 0 01-2-2V5l3-3zM12 11v6M9 14h6',
  // 山河
  'M1 22L6 13l4 4 6-7 4 5V2H1v20z',
  // 算筹
  'M4 2h16v4H4zM4 8h16v2H4zM4 12h12v2H4zM4 16h8v2H4zM16 14v8M12 18h8',
  // 寰宇
  'M12 2a10 10 0 100 20 10 10 0 000-20zM2 12h20M12 2a3 3 0 013 3v14a3 3 0 01-3 3',
  // 药柜
  'M3 3h6v3H3zM11 3h6v3h-6zM3 8h6v3H3zM11 8h6v3h-6zM3 13h6v3H3zM11 13h6v3h-6z',
  // 竹简
  'M6 2h12v4H6zM6 8h12v2H6zM6 12h12v2H6zM6 16h12v4H6zM4 2v18',
];

function skillIcon(id: string) {
  const idx = hashId(id) % ICON_PATHS.length;
  return (
    <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" className="drop-shadow-sm opacity-80">
      <path d={ICON_PATHS[idx]} />
    </svg>
  );
}

interface SkillMeta {
  id: string;
  name: string;
  status: string;
  version: string;
  capabilities: Record<string, unknown>;
}

const CAP_LABELS: Record<string, { label: string; icon: string }> = {
  storage: { label: '存储', icon: '📦' },
  network: { label: '网络', icon: '🌐' },
  ai: { label: 'AI', icon: '🤖' },
  file: { label: '文件', icon: '📁' },
  os: { label: '系统', icon: '🖥' },
  crypto: { label: '加密', icon: '🔐' },
  database: { label: '数据库', icon: '🗄' },
  websearch: { label: '搜索', icon: '🔍' },
  canvas: { label: '画布', icon: '🎨' },
  threejs: { label: '3D', icon: '🧊' },
};

function CapBadge({ cap }: { cap: string }) {
  const info = CAP_LABELS[cap];
  if (!info) return null;
  return (
    <span
      className="inline-flex items-center gap-0.5 px-1 py-0 text-[10px] rounded border bg-background/60 text-muted-foreground"
      title={info.label}
    >
      <span className="text-[10px] leading-none">{info.icon}</span>
    </span>
  );
}

interface SkillsPageProps {
  activeSkillId: string | null;
  initDetail?: Record<string, unknown> | null;
  onOpenModelConfig: () => void;
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

export function SkillsPage({ activeSkillId, initDetail, onOpenModelConfig }: SkillsPageProps) {
  const [skills, setSkills] = useState<SkillMeta[]>([]);
  const [viewMode, setViewMode] = useState<ViewMode>('list');
  const { resolved: theme } = useTheme();
  const isDark = theme === 'dark';

  useEffect(() => {
    const handler = (e: Event) => setSkills((e as CustomEvent).detail.skills || []);
    window.addEventListener('skill-list', handler);
    kernel.send({ type: 'list-skills' });
    return () => window.removeEventListener('skill-list', handler);
  }, []);

  useEffect(() => {
    const handler = () => onInstall();
    window.addEventListener('floating-install', handler);
    return () => window.removeEventListener('floating-install', handler);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

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

  function onUninstall(skillId: string) {
    if (confirm(`确定要卸载 Skill "${skillId}" 吗？`)) {
      kernel.send({ type: 'uninstall-skill', skillId });
    }
  }

  const activeSkill = skills.find((s) => s.id === activeSkillId) ?? null;

  function onSkillClick(skillId: string) {
    if (skillId === activeSkillId) {
      kernel.deactivateSkill(skillId);
    } else {
      kernel.activateSkill(skillId);
    }
  }

  return (
    <div className="flex flex-col flex-1 min-h-0">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b flex-shrink-0">
        <div>
          <h2 className="text-lg font-semibold text-foreground">技能</h2>
          <p className="text-xs text-muted-foreground mt-0.5">
            {skills.length > 0 ? `已安装 ${skills.length} 个模块` : 'AI 驱动的交互式应用模块'}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {skills.length > 0 && (
            <div className="flex items-center gap-2 mr-1">
              <Switch
                checked={viewMode === 'card'}
                onCheckedChange={(checked) => {
                  if (activeSkillId) kernel.deactivateSkill(activeSkillId);
                  setViewMode(checked ? 'card' : 'list');
                }}
                id="view-mode-switch"
              />
              <label htmlFor="view-mode-switch" className="text-xs text-muted-foreground cursor-pointer select-none">
                书架
              </label>
            </div>
          )}
          <Button variant="outline" size="sm" onClick={onInstall}>
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" className="mr-1.5">
              <line x1="8" y1="2" x2="8" y2="14" />
              <line x1="2" y1="8" x2="14" y2="8" />
            </svg>
            安装
          </Button>
        </div>
      </div>

      {/* Empty state */}
      {skills.length === 0 ? (
        <div className="flex-1 flex items-center justify-center">
          <div className="text-center">
            <div className="w-16 h-16 mx-auto mb-4 rounded-2xl bg-muted flex items-center justify-center">
              <svg width="28" height="28" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.2" className="text-muted-foreground">
                <path d="M2 4v8a2 2 0 002 2h8a2 2 0 002-2V4a2 2 0 00-2-2H4a2 2 0 00-2 2z" />
                <path d="M6 6h4M6 9h4" />
              </svg>
            </div>
            <p className="text-sm text-foreground font-medium mb-1">暂无技能</p>
            <p className="text-xs text-muted-foreground mb-4">在首页对话框描述你想要的应用，AI 将自动生成</p>
            <Button variant="outline" size="sm" onClick={onInstall}>从文件夹安装</Button>
          </div>
        </div>
      ) : viewMode === 'list' ? (
        /* ====== List mode: resizable sidebar + workspace ====== */
        <div className="flex-1 min-h-0 relative">
          <WwResizableLayout
            left={
              <div className="h-full flex flex-col">
                <div className="px-4 py-2.5 border-b flex-shrink-0">
                  <span className="text-xs font-medium text-muted-foreground">
                    模块列表 · {skills.length}
                  </span>
                </div>
                <div className="flex-1 overflow-auto">
                  {skills.map((s) => {
                    const isActive = s.id === activeSkillId;
                    return (
                      <button
                        key={s.id}
                        onClick={() => onSkillClick(s.id)}
                        className={`w-full text-left px-4 py-2.5 transition-colors border-b border-border/40 ${
                          isActive
                            ? 'bg-accent/10 border-l-2 border-l-primary'
                            : 'hover:bg-muted/50 border-l-2 border-l-transparent'
                        }`}
                      >
                        <div className="flex items-center gap-2">
                          <span className={`w-2 h-2 rounded-full flex-shrink-0 ${statusDot(s.status)}`} />
                          <span className="text-sm font-medium truncate flex-1">{s.name}</span>
                          {s.capabilities && Object.keys(s.capabilities).filter(c => c !== 'ui' && c !== 'permission').slice(0, 3).map(c => (
                            <CapBadge key={c} cap={c} />
                          ))}
                        </div>
                      </button>
                    );
                  })}
                </div>
              </div>
            }
            right={
              <div className="h-full flex flex-col">
                <SkillPanel
                  skillId={activeSkill?.id}
                  initDetail={initDetail}
                  onDeactivate={activeSkill ? () => kernel.deactivateSkill(activeSkill.id) : undefined}
                  version={activeSkill?.version}
                  status={activeSkill?.status}
                  capabilities={activeSkill?.capabilities}
                  placeholder={
                    <div className="text-center space-y-3">
                      <div className="w-16 h-16 mx-auto rounded-2xl bg-muted flex items-center justify-center">
                        <svg width="28" height="28" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.2">
                          <path d="M8 2v12M2 8h12" />
                        </svg>
                      </div>
                      <p className="text-sm">从左侧选择一个技能模块</p>
                      <p className="text-xs">点击模块名激活，右侧将渲染其界面</p>
                    </div>
                  }
                />
              </div>
            }
          />
        </div>
      ) : (
        /* ====== 古风书格 — 一技一格，互不干扰 ====== */
        <>
          {activeSkill && (
            <div className="flex-1 min-h-0 flex flex-col">
              <SkillPanel
                skillId={activeSkill.id}
                initDetail={initDetail}
                onDeactivate={() => kernel.deactivateSkill(activeSkill.id)}
                version={activeSkill.version}
                status={activeSkill.status}
                capabilities={activeSkill.capabilities}
              />
            </div>
          )}
          {!activeSkill && (
            <ScrollArea className="flex-1">
            <div className="p-6">
              {/* 书格外框 — 流光渐变边框 */}
              <div
                className="rounded-xl overflow-hidden"
                style={{
                  padding: '2px',
                  background: isDark
                    ? 'linear-gradient(135deg, hsl(14,50%,30%), hsl(28,40%,35%), hsl(8,48%,33%), hsl(22,36%,38%), hsl(14,50%,30%))'
                    : 'linear-gradient(135deg, hsl(14,55%,42%), hsl(28,42%,48%), hsl(8,50%,44%), hsl(22,38%,50%), hsl(14,55%,42%))',
                  backgroundSize: '300% 300%',
                  animation: 'bookshelf-border-flow 6s ease infinite',
                  boxShadow: isDark
                    ? '0 8px 48px rgba(0,0,0,0.55), 0 2px 6px rgba(0,0,0,0.3)'
                    : '0 8px 48px rgba(0,0,0,0.12), 0 2px 6px rgba(0,0,0,0.06)',
                }}
              >
                {/* 背板 — 与软件背景一致 */}
                <div className="rounded-lg overflow-hidden bg-background">
                  {/* 墨韵书格 */}
                  <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5">
                    {skills.map((s) => {
                      const color = spineColor(s.id);
                      return (
                        <div
                          key={s.id}
                          onClick={() => onSkillClick(s.id)}
                          className="group cursor-pointer flex flex-col items-center py-7 px-4 relative"
                          style={{
                            borderRight: `1px solid ${isDark ? 'hsl(25,10%,18%)' : 'hsl(30,14%,76%)'}`,
                            borderBottom: `1px solid ${isDark ? 'hsl(25,10%,18%)' : 'hsl(30,14%,76%)'}`,
                          }}
                        >
                          {/* 格子 hover 光晕 */}
                          <div
                            className="absolute inset-1.5 rounded-xl opacity-0 group-hover:opacity-100 transition-opacity duration-500 pointer-events-none"
                            style={{
                              background: isDark
                                ? 'radial-gradient(ellipse at center, rgba(255,255,255,0.03) 0%, transparent 60%)'
                                : 'radial-gradient(ellipse at center, rgba(160,100,40,0.05) 0%, transparent 60%)',
                            }}
                          />

                          {/* 书封 — 3D 纸质感 */}
                          <div
                            className="w-[120px] rounded-lg flex flex-col items-center justify-center relative transition-all duration-500 z-10 overflow-hidden"
                            style={{
                              aspectRatio: '3/4',
                              background: `linear-gradient(145deg, ${spineColor(s.id)}, color-mix(in srgb, ${spineColor(s.id)} 85%, #000) 100%)`,
                              boxShadow: `
                                inset 0 0 40px rgba(0,0,0,0.2),
                                4px 6px 16px rgba(0,0,0,0.3),
                                -1px 0 4px rgba(0,0,0,0.08)
                              `,
                              transform: 'translateY(0) scale(1)',
                            }}
                            onMouseEnter={(e) => {
                              e.currentTarget.style.transform = 'translateY(-8px) scale(1.04)';
                              e.currentTarget.style.boxShadow = `
                                inset 0 0 40px rgba(0,0,0,0.2),
                                8px 20px 40px rgba(0,0,0,0.35),
                                0 0 20px ${color}33
                              `;
                            }}
                            onMouseLeave={(e) => {
                              e.currentTarget.style.transform = 'translateY(0) scale(1)';
                              e.currentTarget.style.boxShadow = `
                                inset 0 0 40px rgba(0,0,0,0.2),
                                4px 6px 16px rgba(0,0,0,0.3),
                                -1px 0 4px rgba(0,0,0,0.08)
                              `;
                            }}
                          >
                            {/* 纸纹理叠加 */}
                            <div
                              className="absolute inset-0 opacity-20"
                              style={{
                                backgroundImage: `
                                  radial-gradient(circle at 20% 30%, rgba(255,255,255,0.3) 0%, transparent 50%),
                                  radial-gradient(circle at 80% 70%, rgba(0,0,0,0.05) 0%, transparent 40%)
                                `,
                              }}
                            />

                            {/* 顶光 */}
                            <div className="absolute top-0 inset-x-0 h-[30%] rounded-t-lg"
                              style={{ background: 'linear-gradient(180deg, rgba(255,255,255,0.25), transparent)' }} />

                            {/* 书封图标 */}
                            <div className="relative z-10 text-white/85 mb-4 transition-transform duration-700 group-hover:scale-110">
                              {skillIcon(s.id)}
                            </div>

                            {/* 朱砂分隔线 */}
                            <div className="relative z-10 w-10 h-[1px] bg-white/40 group-hover:w-14 group-hover:bg-white/60 transition-all duration-500" />

                            {/* 竖排书名 */}
                            <span
                              className="relative z-10 text-white/90 text-[13px] font-medium px-2 mt-4 text-center select-none tracking-widest"
                              style={{ writingMode: 'vertical-rl', lineHeight: 1.8 }}
                            >
                              {s.name.length > 16 ? s.name.slice(0, 16) + '…' : s.name}
                            </span>

                            {/* 左侧装订线 */}
                            <div className="absolute left-0 top-0 bottom-0 w-2 rounded-l-lg"
                              style={{ background: 'linear-gradient(90deg, rgba(0,0,0,0.25), transparent)' }} />

                            {/* 状态徽章 — 右上角 */}
                            <div className="absolute top-2 right-2 z-20">
                              <div className="flex items-center gap-1 px-1.5 py-0.5 rounded-full bg-white/20 backdrop-blur-sm border border-white/10">
                                <span className={`w-1.5 h-1.5 rounded-full ${statusDot(s.status)} ${s.status === 'running' ? 'shadow-[0_0_6px_currentColor]' : ''}`} />
                              </div>
                            </div>

                            {/* hover 删除按钮 */}
                            <button
                              className="absolute -top-2 -right-2 w-5 h-5 rounded-full bg-destructive text-destructive-foreground flex items-center justify-center opacity-0 group-hover:opacity-100 transition-all shadow-md z-30 hover:scale-110"
                              onClick={(e) => { e.stopPropagation(); onUninstall(s.id); }}
                              title="卸载"
                            >
                              <svg width="10" height="10" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                                <line x1="4" y1="4" x2="12" y2="12" />
                                <line x1="12" y1="4" x2="4" y2="12" />
                              </svg>
                            </button>
                          </div>

                          {/* 底部标签 */}
                          <div className="mt-3 text-center w-full relative z-10">
                            <p className="text-[12px] text-foreground/75 font-semibold truncate mx-auto leading-tight tracking-wide"
                              style={{ maxWidth: '130px', fontFamily: "'Noto Serif SC', 'PingFang SC', 'Microsoft YaHei', serif" }}>
                              {s.name}
                            </p>
                            <div className="flex items-center justify-center gap-1.5 mt-1">
                              <span className="text-[10px] text-muted-foreground/40 font-mono tracking-widest">v{s.version}</span>
                              <span className="w-1 h-1 rounded-full bg-muted-foreground/15" />
                              <span className={`text-[10px] font-medium ${s.status === 'running' ? 'text-red-600/70 dark:text-red-400/70' : 'text-muted-foreground/35'}`}>
                                {statusLabel(s.status)}
                              </span>
                            </div>
                            {s.capabilities && Object.keys(s.capabilities).filter(c => c !== 'ui' && c !== 'permission').length > 0 && (
                              <div className="flex items-center justify-center gap-1 mt-1.5">
                                {Object.keys(s.capabilities).filter(c => c !== 'ui' && c !== 'permission').slice(0, 4).map(c => (
                                  <CapBadge key={c} cap={c} />
                                ))}
                              </div>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              </div>
            </div>
          </ScrollArea>
          )}
        </>
      )}
    </div>
  );
}
