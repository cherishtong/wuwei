import { useState, useEffect, useCallback, useRef } from 'react';
import { kernel } from '@/kernel';
import { Card, CardContent, CardHeader, CardTitle } from '@/wv-components/ui/card';
import { Progress } from '@/wv-components/ui/progress';
import { ChartContainer, ChartTooltip, ChartTooltipContent } from '@/wv-components/ui/chart';
import { AreaChart, Area, XAxis, YAxis, ResponsiveContainer } from 'recharts';
import { Cpu, HardDrive, Activity, Globe, Zap, Layers, MemoryStick, Clock, Terminal } from 'lucide-react';

interface Metrics {
  uptimeMs: number; pid: number; threadCount: number; peakThreadCount: number;
  heapUsedMB: number; heapMaxMB: number; nonHeapUsedMB: number;
  activeSkills: number; loadedSkills: number; wsSessions: number;
  skillFileCount: number; skillDiskMB: number;
  system: { availableProcessors: number; loadAvg: number; osArch: string; totalPhysicalMB: number; freePhysicalMB: number; committedVirtualMB: number };
  garbageCollectors: { name: string; collectionCount: number; collectionTimeMs: number }[];
  graalvm: { vmName: string; vmVersion: string; imageKind: string };
  process: { totalMemoryBytes: number; freeMemoryBytes: number; maxMemoryBytes: number };
  capabilityStats?: { total: number; byCapability: Record<string,number> };
  skillCapabilities?: { skillId: string; name: string; capabilities: string[] }[];
  relatedProcesses: { pid: number; command: string; cpuMs: number }[];
}

function fmtUptime(ms: number) { var s=Math.floor(ms/1000),m=Math.floor(s/60),h=Math.floor(m/60); return h>0?h+'h'+m%60+'m':m>0?m+'m'+s%60+'s':s+'s'; }
function fmtMB(mb: number) { return mb>=1024?(mb/1024).toFixed(1)+'GB':mb.toFixed(0)+'MB'; }
function fmtBytes(b: number) { return b>=1073741824?(b/1073741824).toFixed(1)+'GB':b>=1048576?(b/1048576).toFixed(0)+'MB':(b/1024).toFixed(0)+'KB'; }

const MAX_HISTORY = 60;

export function SystemMonitor() {
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [history, setHistory] = useState<{ time: number; heap: number }[]>([]);
  const poll = useCallback(() => kernel.getMetrics(), []);

  useEffect(() => {
    const h = (e: Event) => { const d=(e as CustomEvent).detail; if(d?.type==='kernel-metrics') {
      var m = d as Metrics;
      setMetrics(m);
      setHistory(prev => { var now = Date.now(); var next = [...prev, { time: now, heap: m.heapUsedMB }]; if (next.length > MAX_HISTORY) next = next.slice(-MAX_HISTORY); return next; });
    }};
    window.addEventListener('kernel-metrics', h); poll(); var i=setInterval(poll,3000);
    return () => { window.removeEventListener('kernel-metrics', h); clearInterval(i); };
  }, [poll]);

  if (!metrics) return <div className="p-4 text-sm text-muted-foreground">加载中...</div>;
  var m = metrics;

  var chartConfig = { heap: { label: 'Heap (MB)', color: 'hsl(var(--primary))' } };
  var hp = m.heapMaxMB>0 ? (m.heapUsedMB/m.heapMaxMB)*100 : 0;
  var sysMemUsed = m.system.totalPhysicalMB - m.system.freePhysicalMB;
  var sysMemPct = m.system.totalPhysicalMB>0 ? (sysMemUsed/m.system.totalPhysicalMB)*100 : 0;

  return (
    <div className="p-4 space-y-4">
      {/* Hero row */}
      <div className="grid grid-cols-5 gap-3">
        <Card className="col-span-2 bg-gradient-to-br from-primary/10 to-primary/5 border-primary/20">
          <CardContent className="p-4 flex items-center gap-3">
            <div className="w-10 h-10 rounded-full bg-primary/20 flex items-center justify-center"><Zap size={18} className="text-primary" /></div>
            <div>
              <div className="text-xs text-muted-foreground">{m.graalvm.vmName} {m.graalvm.vmVersion}</div>
              <div className="text-lg font-bold">PID {m.pid} · 运行 {fmtUptime(m.uptimeMs)}</div>
              <div className="text-[10px] text-muted-foreground">{m.graalvm.imageKind} · {m.system.osArch}</div>
            </div>
          </CardContent>
        </Card>
        <Card className="bg-gradient-to-br from-emerald-500/10 to-emerald-500/5 border-emerald-500/20">
          <CardContent className="p-4 flex items-center gap-3">
            <Cpu size={20} className="text-emerald-500" />
            <div>
              <div className="text-xs text-muted-foreground">CPU</div>
              <div className="text-lg font-bold">{m.system.availableProcessors} 核 · 负载 {m.system.loadAvg.toFixed(1)}</div>
              <div className="text-[10px] text-muted-foreground">{m.threadCount} 线程 / 峰值 {m.peakThreadCount}</div>
            </div>
          </CardContent>
        </Card>
        <Card className="bg-gradient-to-br from-amber-500/10 to-amber-500/5 border-amber-500/20">
          <CardContent className="p-4 flex items-center gap-3">
            <Globe size={20} className="text-amber-500" />
            <div>
              <div className="text-xs text-muted-foreground">连接 & 技能</div>
              <div className="text-lg font-bold">WS {m.wsSessions} · 技能 {m.activeSkills}/{m.loadedSkills}</div>
            </div>
          </CardContent>
        </Card>
        <Card className="bg-gradient-to-br from-violet-500/10 to-violet-500/5 border-violet-500/20">
          <CardContent className="p-4 flex items-center gap-3">
            <HardDrive size={20} className="text-violet-500" />
            <div>
              <div className="text-xs text-muted-foreground">磁盘</div>
              <div className="text-lg font-bold">{fmtMB(m.skillDiskMB)}</div>
              <div className="text-[10px] text-muted-foreground">{m.skillFileCount} 个文件</div>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Memory charts row */}
      <div className="grid grid-cols-2 gap-3">
        <Card>
          <CardHeader className="p-3 pb-0"><CardTitle className="text-xs flex items-center gap-1.5"><MemoryStick size={12}/>堆内存 ({fmtMB(m.heapUsedMB)} / {fmtMB(m.heapMaxMB)})</CardTitle></CardHeader>
          <CardContent className="p-3 pt-1">
            <Progress value={hp} className="h-3 mb-2" />
            <ChartContainer config={chartConfig} className="h-32 w-full">
              <ResponsiveContainer>
                <AreaChart data={history} margin={{ top: 5, right: 5, left: 0, bottom: 0 }}>
                  <defs><linearGradient id="heapGrad" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="hsl(var(--primary))" stopOpacity={0.4}/><stop offset="100%" stopColor="hsl(var(--primary))" stopOpacity={0}/></linearGradient></defs>
                  <XAxis dataKey="time" hide />
                  <Area dataKey="heap" stroke="hsl(var(--primary))" fill="url(#heapGrad)" strokeWidth={2} isAnimationActive={false} />
                  <ChartTooltip content={<ChartTooltipContent />} />
                </AreaChart>
              </ResponsiveContainer>
            </ChartContainer>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="p-3 pb-0"><CardTitle className="text-xs flex items-center gap-1.5"><Activity size={12}/>系统内存 ({fmtMB(sysMemUsed)} / {fmtMB(m.system.totalPhysicalMB)})</CardTitle></CardHeader>
          <CardContent className="p-3 pt-1 space-y-2">
            <Progress value={sysMemPct} className="h-3" />
            <div className="grid grid-cols-2 gap-1 text-[10px] text-muted-foreground">
              <span>可用 {fmtMB(m.system.freePhysicalMB)}</span><span>虚拟 {fmtMB(m.system.committedVirtualMB)}</span>
              <span>非堆 {fmtMB(m.nonHeapUsedMB)}</span>
              <span>进程 {fmtBytes(m.process.totalMemoryBytes)}</span>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* GC + Skills + Processes row */}
      <div className="grid grid-cols-3 gap-3">
        <Card>
          <CardHeader className="p-3 pb-1"><CardTitle className="text-xs">GC 回收</CardTitle></CardHeader>
          <CardContent className="p-3 pt-0 space-y-2">
            {m.garbageCollectors.map(function(gc,i){return (
              <div key={i} className="flex justify-between text-[11px]"><span className="text-muted-foreground">{gc.name}</span><span className="font-mono">{gc.collectionCount} 次 ({gc.collectionTimeMs}ms)</span></div>
            );})}
          </CardContent>
        </Card>

        <Card className="col-span-2">
          <CardHeader className="p-3 pb-1"><CardTitle className="text-xs flex items-center gap-1.5"><Layers size={12}/>技能能力 ({m.loadedSkills})</CardTitle></CardHeader>
          <CardContent className="p-3 pt-0">
            <div className="grid grid-cols-2 gap-x-4 gap-y-1 max-h-48 overflow-auto">
              {m.skillCapabilities?.map(function(s) {
                return (
                  <div key={s.skillId} className="flex items-center gap-1.5 text-[10px] py-0.5 border-b border-border/30">
                    <span className="truncate w-24 text-muted-foreground">{s.name || s.skillId}</span>
                    <span className="flex gap-0.5 flex-1 justify-end">
                      {s.capabilities.map(function(c){return <span key={c} className="px-1 py-0.5 rounded bg-primary/10 text-primary text-[9px]">{c}</span>;})}
                    </span>
                  </div>
                );
              })}
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="p-3 pb-1"><CardTitle className="text-xs flex items-center gap-1.5"><Terminal size={12}/>进程 ({m.relatedProcesses?.length || 0})</CardTitle></CardHeader>
          <CardContent className="p-3 pt-0 space-y-1">
            {m.relatedProcesses?.slice(0,6).map(function(p,i){return (
              <div key={i} className="flex items-center gap-2 text-[10px]">
                <span className="font-mono w-14 shrink-0 text-muted-foreground">PID {p.pid}</span>
                <span className="truncate flex-1">{p.command.split('\\').pop()}</span>
              </div>
            );})}
          </CardContent>
        </Card>
      </div>

      <div className="text-[10px] text-muted-foreground text-center">{m.graalvm.vmName} {m.graalvm.vmVersion} · 每 3s 刷新</div>
    </div>
  );
}
