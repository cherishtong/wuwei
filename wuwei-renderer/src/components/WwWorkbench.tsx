import { useState, useEffect } from 'react';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/wv-components/ui/tabs';
import { ScrollArea } from '@/wv-components/ui/scroll-area';
import { PrismLight as SyntaxHighlighter } from 'react-syntax-highlighter';
import jsonLang from 'react-syntax-highlighter/dist/esm/languages/prism/json';
import jsLang from 'react-syntax-highlighter/dist/esm/languages/prism/javascript';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';

SyntaxHighlighter.registerLanguage('json', jsonLang);
SyntaxHighlighter.registerLanguage('javascript', jsLang);

interface SkillSource {
  skillId: string;
  skillJson: string;
  uiJson: string;
  handlersJs: string;
}

interface WwWorkbenchProps {
  onClose: () => void;
}

export function WwWorkbench({ onClose }: WwWorkbenchProps) {
  const [source, setSource] = useState<SkillSource | null>(null);
  const [activeTab, setActiveTab] = useState('skillJson');

  useEffect(() => {
    const handler = (e: Event) => {
      setSource((e as CustomEvent).detail as SkillSource);
      setActiveTab('skillJson');
    };
    window.addEventListener('skill-source', handler);
    return () => window.removeEventListener('skill-source', handler);
  }, []);

  if (!source) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-sm text-muted-foreground gap-2 animate-fade-in">
        <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" className="text-muted-foreground/40">
          <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
          <polyline points="14 2 14 8 20 8" />
          <line x1="16" y1="13" x2="8" y2="13" />
          <line x1="16" y1="17" x2="8" y2="17" />
          <polyline points="10 9 9 9 8 9" />
        </svg>
        <p>请从技能标题栏点击查看源码</p>
      </div>
    );
  }

  function formatJson(json: string): string {
    try { return JSON.stringify(JSON.parse(json), null, 2); } catch { return json; }
  }

  function getContent(): { code: string; language: string } {
    switch (activeTab) {
      case 'skillJson': return { code: formatJson(source!.skillJson), language: 'json' };
      case 'uiJson': return { code: formatJson(source!.uiJson), language: 'json' };
      case 'handlersJs': return { code: source!.handlersJs, language: 'javascript' };
      default: return { code: '', language: 'json' };
    }
  }

  const { code, language } = getContent();

  return (
    <div className="flex flex-col h-full animate-fade-in">
      {/* Header */}
      <div className="flex items-center gap-2 px-4 py-3 border-b flex-shrink-0">
        <div className="flex-1 min-w-0">
          <p className="text-sm font-semibold font-mono truncate">{source.skillId}</p>
          <p className="text-[10px] text-muted-foreground">源码预览</p>
        </div>
        <button
          className="p-1.5 rounded-md hover:bg-accent transition-colors text-muted-foreground hover:text-foreground"
          onClick={onClose}
          title="关闭"
        >
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
            <line x1="4" y1="4" x2="12" y2="12" />
            <line x1="12" y1="4" x2="4" y2="12" />
          </svg>
        </button>
      </div>

      {/* Tabs */}
      <div className="px-4 pt-3 flex-shrink-0">
        <Tabs value={activeTab} onValueChange={setActiveTab}>
          <TabsList className="w-full">
            <TabsTrigger value="skillJson" className="flex-1 text-xs">
              skill.json
            </TabsTrigger>
            <TabsTrigger value="uiJson" className="flex-1 text-xs">
              ui.json
            </TabsTrigger>
            <TabsTrigger value="handlersJs" className="flex-1 text-xs">
              handlers.js
            </TabsTrigger>
          </TabsList>
        </Tabs>
      </div>

      {/* Content */}
      <div className="flex-1 min-h-0 p-4">
        <ScrollArea className="h-full">
          <SyntaxHighlighter
            language={language}
            style={oneDark}
            customStyle={{
              margin: 0,
              borderRadius: '0.5rem',
              fontSize: '0.8125rem',
              lineHeight: '1.6',
            }}
            showLineNumbers
          >
            {code}
          </SyntaxHighlighter>
        </ScrollArea>
      </div>
    </div>
  );
}
