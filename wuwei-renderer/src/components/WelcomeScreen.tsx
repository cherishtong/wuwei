import { useState, useEffect } from 'react';
import { Button } from '@/wv-components/ui/button';
import { kernel } from '../kernel';

interface SkillMeta {
  id: string;
  name: string;
  status: string;
  version: string;
}

export function WelcomeScreen() {
  const [skills, setSkills] = useState<SkillMeta[]>([]);

  useEffect(() => {
    const handler = (e: Event) => {
      setSkills((e as CustomEvent).detail.skills || []);
    };
    window.addEventListener('skill-list', handler);
    return () => window.removeEventListener('skill-list', handler);
  }, []);

  const suggestions = [
    '一个待办事项列表',
    '一个数据表格，支持排序和过滤',
    '一个 Markdown 编辑器，带实时预览',
    '一个简单的聊天界面',
  ];

  return (
    <div className="flex items-center justify-center h-full">
      <div className="max-w-lg w-full px-8 py-12 text-center animate-fade-in">
        {/* Logo area */}
        <div className="mb-8">
          <div className="w-16 h-16 mx-auto mb-4 rounded-2xl bg-gradient-to-br from-primary to-primary/60 flex items-center justify-center shadow-lg">
            <span className="text-2xl font-bold text-primary-foreground">无</span>
          </div>
          <h1 className="text-2xl font-semibold text-foreground mb-1">
            无为 <span className="text-muted-foreground font-normal">Wuwei</span>
          </h1>
          <p className="text-sm text-muted-foreground">
            用自然语言描述你想要的应用，AI 会自动生成交互界面
          </p>
        </div>

        {/* Quick start prompts */}
        <div className="grid grid-cols-2 gap-2 mb-6">
          {suggestions.map((s) => (
            <button
              key={s}
              className="text-left px-3 py-2.5 rounded-lg border hover:bg-accent transition-colors text-sm text-muted-foreground hover:text-foreground"
              onClick={() => kernel.sendIntent(s)}
            >
              {s}
            </button>
          ))}
        </div>

        {/* Recent skills */}
        {skills.length > 0 && (
          <div className="text-left">
            <p className="text-xs font-medium text-muted-foreground mb-2 uppercase tracking-wider">
              已安装的 Skills
            </p>
            <div className="space-y-1">
              {skills.slice(0, 5).map((s) => (
                <button
                  key={s.id}
                  className="w-full flex items-center gap-2 px-3 py-2 rounded-md hover:bg-accent transition-colors text-sm text-left"
                  onClick={() => kernel.activateSkill(s.id)}
                >
                  <span
                    className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${
                      s.status === 'running' ? 'bg-green-500' : 'bg-gray-400'
                    }`}
                  />
                  <span className="truncate">{s.name}</span>
                  <span className="text-xs text-muted-foreground ml-auto">v{s.version}</span>
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Keyboard hint */}
        <p className="mt-8 text-xs text-muted-foreground">
          Ctrl+B 切换侧边栏 &middot; Ctrl+` 切换终端 &middot; Enter 发送
        </p>
      </div>
    </div>
  );
}
