import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { kernel } from '@/kernel';
import { Tabs, TabsList, TabsTrigger } from '@/wv-components/ui/tabs';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/wv-components/ui/select';
import { Button } from '@/wv-components/ui/button';
import { useTheme } from '../contexts/ThemeContext';
import { RefreshCw } from 'lucide-react';

const today = new Date().toISOString().slice(0, 10);
const LINE_HEIGHT = 18;
const OVERSCAN = 20;

export function LogViewer() {
  const { resolved: theme } = useTheme();
  const isDark = theme === 'dark';
  const [source, setSource] = useState<'kernel' | 'render'>('kernel');
  const [date, setDate] = useState(today);
  const [dates, setDates] = useState<string[]>([today]);
  const [rawContent, setRawContent] = useState('');
  const [autoScroll, setAutoScroll] = useState(true);
  const containerRef = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [viewHeight, setViewHeight] = useState(400);

  const lines = useMemo(() => {
    if (!rawContent) return [];
    return rawContent.split('\n').reverse();
  }, [rawContent]);

  const totalHeight = lines.length * LINE_HEIGHT;
  const startIdx = Math.max(0, Math.floor(scrollTop / LINE_HEIGHT) - OVERSCAN);
  const endIdx = Math.min(lines.length, Math.ceil((scrollTop + viewHeight) / LINE_HEIGHT) + OVERSCAN);
  const visibleLines = lines.slice(startIdx, endIdx);
  const offsetY = startIdx * LINE_HEIGHT;

  const loadDates = useCallback(() => {
    const handler = (e: Event) => {
      const d = (e as CustomEvent).detail;
      if (d?.type === 'log-dates') setDates(d.dates || [today]);
    };
    window.addEventListener('log-dates', handler);
    kernel.listLogDates(source);
    return () => window.removeEventListener('log-dates', handler);
  }, [source]);

  useEffect(() => { return loadDates(); }, [loadDates]);

  useEffect(() => {
    const handler = (e: Event) => {
      const d = (e as CustomEvent).detail;
      if (d?.type === 'log-content') {
        setRawContent(d.content || '');
        if (autoScroll) setScrollTop(0);
      }
    };
    window.addEventListener('log-content', handler);
    kernel.getLog(source, date);
    return () => window.removeEventListener('log-content', handler);
  }, [source, date]);

  // Auto-refresh today, update container height
  useEffect(() => {
    if (date !== today) return;
    var interval = setInterval(() => kernel.getLog(source, today), 5000);
    return () => clearInterval(interval);
  }, [source, date]);

  useEffect(() => {
    if (!containerRef.current) return;
    var ro = new ResizeObserver(function (entries) {
      setViewHeight(entries[0].contentRect.height);
    });
    ro.observe(containerRef.current);
    return () => ro.disconnect();
  }, []);

  var onScroll = useCallback(function (e: React.UIEvent<HTMLDivElement>) {
    setScrollTop(e.currentTarget.scrollTop);
    // Track if user scrolled away from top
    if (e.currentTarget.scrollTop > 50) setAutoScroll(false);
  }, []);

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-center gap-2 px-4 py-2 border-b flex-shrink-0">
        <span className="text-sm font-semibold mr-2">日志</span>
        <Tabs value={source} onValueChange={(v) => { setSource(v as 'kernel' | 'render'); setDate(today); }}>
          <TabsList>
            <TabsTrigger value="kernel" className="text-xs">内核</TabsTrigger>
            <TabsTrigger value="render" className="text-xs">前端</TabsTrigger>
          </TabsList>
        </Tabs>
        <Select value={date} onValueChange={setDate}>
          <SelectTrigger className="w-36 h-8 text-xs"><SelectValue /></SelectTrigger>
          <SelectContent>{dates.map(d => <SelectItem key={d} value={d} className="text-xs">{d}</SelectItem>)}</SelectContent>
        </Select>
        <div className="flex-1" />
        <Button variant="ghost" size="sm" onClick={() => { setAutoScroll(true); setScrollTop(0); }} className="h-6 text-[10px]">
          {autoScroll ? '跟随中' : '回顶部'}
        </Button>
        <Button variant="ghost" size="sm" onClick={() => kernel.getLog(source, date)} className="h-7 w-7 p-0">
          <RefreshCw size={12} />
        </Button>
      </div>

      <div ref={containerRef} className="flex-1 overflow-auto" style={{ background: isDark ? '#111' : '#f8f8f8' }} onScroll={onScroll}>
        <div style={{ height: totalHeight, position: 'relative' }}>
          <div style={{ position: 'absolute', top: offsetY, left: 0, right: 0 }}>
            {visibleLines.map(function (line, i) {
              return (
                <div key={startIdx + i} className="px-4 text-xs font-mono leading-relaxed whitespace-nowrap" style={{ height: LINE_HEIGHT, color: isDark ? '#00ff41' : '#0a0' }}>
                  {line || ' '}
                </div>
              );
            })}
          </div>
        </div>
      </div>

      <div className="flex items-center px-4 py-1 border-t flex-shrink-0 text-[10px] text-muted-foreground">
        <span>{source === 'kernel' ? '内核' : '前端'} · {date} · {lines.length.toLocaleString()} 行</span>
        {date === today && <span className="ml-2 text-green-500">● 实时</span>}
      </div>
    </div>
  );
}
