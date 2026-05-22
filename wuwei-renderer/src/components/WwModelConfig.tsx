import { useState, useEffect } from 'react';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/wv-components/ui/dialog';
import { Button } from '@/wv-components/ui/button';
import { Input } from '@/wv-components/ui/input';
import { ScrollArea } from '@/wv-components/ui/scroll-area';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/wv-components/ui/select';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/wv-components/ui/table';
import { kernel } from '../kernel';

interface WwModelConfigProps {
  open: boolean;
  onClose: () => void;
}

interface RouteEntry {
  provider: string;
  model: string;
  apiUrl: string;
  apiKey: string;
  params: string;
}

const providers = ['openai', 'anthropic', 'deepseek', 'google'];

function maskKey(key: string): string {
  if (!key || key.length < 8) return key;
  return key.slice(0, 4) + '••••' + key.slice(-4);
}

export function WwModelConfig({ open, onClose }: WwModelConfigProps) {
  const [modelRoutes, setModelRoutes] = useState<Record<string, RouteEntry>>({});
  const [edits, setEdits] = useState<Record<string, RouteEntry>>({});
  const [newTaskType, setNewTaskType] = useState('');
  const [newProvider, setNewProvider] = useState('openai');
  const [newModel, setNewModel] = useState('');
  const [newApiUrl, setNewApiUrl] = useState('');
  const [newApiKey, setNewApiKey] = useState('');
  const [newParams, setNewParams] = useState('');

  useEffect(() => {
    const onList = (e: Event) => {
      setModelRoutes((e as CustomEvent).detail.entries || {});
      setEdits({});
    };
    const onUpdated = (e: Event) => {
      const { taskType, provider, model, apiUrl, apiKey, params } = (e as CustomEvent).detail;
      setModelRoutes((prev) => ({
        ...prev,
        [taskType]: { provider, model, apiUrl: apiUrl || '', apiKey: apiKey || '', params: params || '{}' },
      }));
      setEdits((prev) => {
        const next = { ...prev };
        delete next[taskType];
        return next;
      });
    };
    const onDeleted = (e: Event) => {
      const { taskType } = (e as CustomEvent).detail;
      setModelRoutes((prev) => {
        const next = { ...prev };
        delete next[taskType];
        return next;
      });
    };

    window.addEventListener('model-routing-list', onList);
    window.addEventListener('model-routing-updated', onUpdated);
    window.addEventListener('model-routing-deleted', onDeleted);

    return () => {
      window.removeEventListener('model-routing-list', onList);
      window.removeEventListener('model-routing-updated', onUpdated);
      window.removeEventListener('model-routing-deleted', onDeleted);
    };
  }, []);

  useEffect(() => {
    if (open) {
      kernel.listModelRouting();
      setNewTaskType('');
      setNewModel('');
      setNewApiUrl('');
      setNewApiKey('');
      setNewParams('');
      setNewProvider('openai');
    }
  }, [open]);

  function getCurrent(taskType: string): RouteEntry {
    return modelRoutes[taskType] ?? { provider: '', model: '', apiUrl: '', apiKey: '', params: '{}' };
  }

  function field(taskType: string, field: keyof RouteEntry): string {
    const current = getCurrent(taskType);
    return edits[taskType]?.[field] ?? current[field] ?? '';
  }

  function setField(taskType: string, field: keyof RouteEntry, value: string) {
    setEdits((prev) => ({
      ...prev,
      [taskType]: { ...getCurrent(taskType), ...prev[taskType], [field]: value },
    }));
  }

  function saveRouting(taskType: string) {
    const e = edits[taskType];
    const c = getCurrent(taskType);
    kernel.setModelRouting(
      taskType,
      e?.provider ?? c.provider,
      e?.model ?? c.model,
      e?.apiUrl ?? c.apiUrl,
      e?.apiKey ?? c.apiKey,
      e?.params ?? c.params,
    );
  }

  function deleteRouting(taskType: string) {
    kernel.deleteModelRouting(taskType);
  }

  function addRouting() {
    const tt = newTaskType.trim();
    if (!tt || !newModel.trim()) return;
    kernel.setModelRouting(tt, newProvider, newModel.trim(), newApiUrl.trim(), newApiKey.trim(), newParams.trim() || '{}');
    setNewTaskType('');
    setNewModel('');
    setNewApiUrl('');
    setNewApiKey('');
    setNewParams('');
    setNewProvider('openai');
  }

  function hasEdit(taskType: string): boolean {
    const e = edits[taskType];
    if (!e) return false;
    const c = getCurrent(taskType);
    return e.provider !== c.provider || e.model !== c.model
      || e.apiUrl !== c.apiUrl || e.apiKey !== c.apiKey || e.params !== c.params;
  }

  function paramPreview(params: string): string {
    try {
      const o = JSON.parse(params);
      const p: string[] = [];
      if (o.temperature !== undefined) p.push(`temp=${o.temperature}`);
      if (o.max_tokens !== undefined) p.push(`max_tok=${o.max_tokens}`);
      if (o.top_p !== undefined) p.push(`top_p=${o.top_p}`);
      return p.length > 0 ? p.join(', ') : params;
    } catch {
      return params;
    }
  }

  const entries = Object.entries(modelRoutes);

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) onClose(); }}>
      <DialogContent className="max-w-[95vw] max-h-[85vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>模型路由配置</DialogTitle>
        </DialogHeader>

        <div className="flex flex-col flex-1 min-h-0 gap-3 mt-2">
          <ScrollArea className="flex-1 max-h-[55vh]">
            <div className="min-w-[900px]">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="text-xs py-2 px-2">任务类型</TableHead>
                    <TableHead className="text-xs py-2 px-2 w-[110px]">厂商</TableHead>
                    <TableHead className="text-xs py-2 px-2 w-[200px]">API 地址</TableHead>
                    <TableHead className="text-xs py-2 px-2 w-[160px]">模型名称</TableHead>
                    <TableHead className="text-xs py-2 px-2 w-[160px]">API Key</TableHead>
                    <TableHead className="text-xs py-2 px-2 w-[140px]">模型参数</TableHead>
                    <TableHead className="text-xs py-2 px-2 w-[80px]">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {entries.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={7} className="text-center text-xs text-muted-foreground py-6">
                        暂无模型路由配置
                      </TableCell>
                    </TableRow>
                  ) : (
                    entries.map(([taskType, route]) => {
                      const dirty = hasEdit(taskType);
                      return (
                        <TableRow key={taskType}>
                          <TableCell className="text-xs py-1.5 px-2 font-mono align-top">
                            {taskType}
                          </TableCell>
                          <TableCell className="text-xs py-1.5 px-2 align-top">
                            <Select value={field(taskType, 'provider')} onValueChange={(v) => setField(taskType, 'provider', v)}>
                              <SelectTrigger className="h-7 text-xs">
                                <SelectValue />
                              </SelectTrigger>
                              <SelectContent>
                                {providers.map((p) => (
                                  <SelectItem key={p} value={p} className="text-xs">{p}</SelectItem>
                                ))}
                              </SelectContent>
                            </Select>
                          </TableCell>
                          <TableCell className="text-xs py-1.5 px-2 align-top">
                            <Input
                              className="h-7 text-xs font-mono"
                              value={field(taskType, 'apiUrl')}
                              onChange={(e) => setField(taskType, 'apiUrl', e.target.value)}
                              placeholder="https://api.openai.com/v1"
                            />
                          </TableCell>
                          <TableCell className="text-xs py-1.5 px-2 align-top">
                            <Input
                              className="h-7 text-xs"
                              value={field(taskType, 'model')}
                              onChange={(e) => setField(taskType, 'model', e.target.value)}
                            />
                          </TableCell>
                          <TableCell className="text-xs py-1.5 px-2 align-top">
                            <Input
                              className="h-7 text-xs font-mono"
                              type="password"
                              value={field(taskType, 'apiKey')}
                              onChange={(e) => setField(taskType, 'apiKey', e.target.value)}
                              placeholder={route.apiKey ? maskKey(route.apiKey) : '留空=全局 Key'}
                            />
                          </TableCell>
                          <TableCell className="text-xs py-1.5 px-2 align-top">
                            <Input
                              className="h-7 text-xs font-mono"
                              value={field(taskType, 'params')}
                              onChange={(e) => setField(taskType, 'params', e.target.value)}
                              placeholder='{"temperature":0.7}'
                              title={field(taskType, 'params') ? paramPreview(field(taskType, 'params')) : undefined}
                            />
                          </TableCell>
                          <TableCell className="text-xs py-1.5 px-2 align-top">
                            <div className="flex items-center gap-1">
                              {dirty && (
                                <Button
                                  size="sm"
                                  variant="outline"
                                  className="h-6 text-[10px] px-2"
                                  onClick={() => saveRouting(taskType)}
                                >
                                  保存
                                </Button>
                              )}
                              <button
                                className="p-1 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-colors"
                                onClick={() => deleteRouting(taskType)}
                                title="删除"
                              >
                                <svg width="11" height="11" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                                  <path d="M2 4h12M5.33 4V2.67a.67.67 0 01.67-.67h4a.67.67 0 01.67.67V4M6.67 7v5M9.33 7v5" />
                                  <path d="M3.33 4l.94 9.33c.05.37.37.67.75.67h5.96c.38 0 .7-.3.75-.67L12.67 4" />
                                </svg>
                              </button>
                            </div>
                          </TableCell>
                        </TableRow>
                      );
                    })
                  )}
                </TableBody>
              </Table>
            </div>
          </ScrollArea>

          {/* Add new routing row */}
          <div className="flex-shrink-0 border-t pt-3 flex items-center gap-1.5 min-w-[900px]">
            <Input
              className="h-7 text-xs flex-[2]"
              placeholder="任务类型"
              value={newTaskType}
              onChange={(e) => setNewTaskType(e.target.value)}
            />
            <Select value={newProvider} onValueChange={setNewProvider}>
              <SelectTrigger className="h-7 text-xs flex-[1.5]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {providers.map((p) => (
                  <SelectItem key={p} value={p} className="text-xs">{p}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Input
              className="h-7 text-xs flex-[2.5] font-mono"
              placeholder="API 地址"
              value={newApiUrl}
              onChange={(e) => setNewApiUrl(e.target.value)}
            />
            <Input
              className="h-7 text-xs flex-[2]"
              placeholder="模型名称"
              value={newModel}
              onChange={(e) => setNewModel(e.target.value)}
            />
            <Input
              className="h-7 text-xs flex-[2] font-mono"
              type="password"
              placeholder="API Key"
              value={newApiKey}
              onChange={(e) => setNewApiKey(e.target.value)}
            />
            <Input
              className="h-7 text-xs flex-[2] font-mono"
              placeholder='{"temperature":0.7}'
              value={newParams}
              onChange={(e) => setNewParams(e.target.value)}
            />
            <Button
              size="sm"
              className="h-7 text-xs flex-shrink-0"
              onClick={addRouting}
              disabled={!newTaskType.trim() || !newModel.trim()}
            >
              添加
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
