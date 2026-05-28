import { useState, useEffect } from 'react';
import { Button } from '@/wv-components/ui/button';
import { kernel } from '../kernel';
import { useTheme } from '../contexts/ThemeContext';

interface SystemPageProps {
  onOpenModelConfig: () => void;
}

export function SystemPage({ onOpenModelConfig }: SystemPageProps) {
  const { resolved, toggle } = useTheme();
  const [kernelSettings, setKernelSettings] = useState<string | null>(null);

  useEffect(() => {
    const handler = (e: Event) => {
      setKernelSettings(JSON.stringify((e as CustomEvent).detail, null, 2));
    };
    window.addEventListener('kernel-settings', handler);
    kernel.send({ type: 'get-settings' });
    return () => window.removeEventListener('kernel-settings', handler);
  }, []);

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-center justify-between px-6 py-4 border-b">
        <div>
          <h2 className="text-lg font-semibold text-foreground">系统</h2>
          <p className="text-xs text-muted-foreground mt-0.5">内核配置与应用设置</p>
        </div>
      </div>

      <div className="flex-1 overflow-auto p-6 space-y-6 max-w-2xl">
        {/* Appearance */}
        <section>
          <h3 className="text-sm font-semibold text-foreground mb-3">外观</h3>
          <div className="flex items-center justify-between p-4 rounded-lg border">
            <div>
              <div className="text-sm font-medium">主题模式</div>
              <div className="text-xs text-muted-foreground mt-0.5">
                当前: {resolved === 'dark' ? '深色' : '浅色'}
              </div>
            </div>
            <Button variant="outline" size="sm" onClick={toggle}>
              {resolved === 'dark' ? '切换到浅色' : '切换到深色'}
            </Button>
          </div>
        </section>

        {/* Kernel */}
        <section>
          <h3 className="text-sm font-semibold text-foreground mb-3">内核</h3>
          <div className="space-y-3">
            <div className="flex items-center justify-between p-4 rounded-lg border">
              <div>
                <div className="text-sm font-medium">模型配置</div>
                <div className="text-xs text-muted-foreground mt-0.5">管理 LLM 提供商与模型</div>
              </div>
              <Button variant="outline" size="sm" onClick={onOpenModelConfig}>配置</Button>
            </div>
            <div className="flex items-center justify-between p-4 rounded-lg border">
              <div>
                <div className="text-sm font-medium">刷新 Skill 列表</div>
                <div className="text-xs text-muted-foreground mt-0.5">重新加载已安装的技能</div>
              </div>
              <Button variant="outline" size="sm" onClick={() => kernel.send({ type: 'list-skills' })}>
                刷新
              </Button>
            </div>
          </div>
        </section>

        {/* About */}
        <section>
          <h3 className="text-sm font-semibold text-foreground mb-3">关于</h3>
          <div className="p-4 rounded-lg border">
            <div className="text-sm font-medium">无为 Wuwei</div>
            <div className="text-xs text-muted-foreground mt-1 space-y-0.5">
              <p>版本 6.4.0</p>
              <p>AI 驱动的应用生成与运行平台</p>
              <p className="mt-2" style={{ fontFamily: '"Noto Serif SC", "Songti SC", serif', fontStyle: 'italic', opacity: 0.6 }}>
                大巧若拙，大音希声
              </p>
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}
