import { useState, useEffect } from 'react';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/wv-components/ui/dialog';
import { Button } from '@/wv-components/ui/button';
import { Badge } from '@/wv-components/ui/badge';
import { kernel } from '../kernel';

interface GateRequest {
  skillId: string;
  capName: string;
  reason: string;
}

export function WwGateDialog() {
  const [open, setOpen] = useState(false);
  const [request, setRequest] = useState<GateRequest | null>(null);

  useEffect(() => {
    const handler = (e: Event) => {
      const d = (e as CustomEvent).detail;
      setRequest({
        skillId: d.skillId || d['skill-id'],
        capName: d.capName || d['cap-name'],
        reason: d.reason || '',
      });
      setOpen(true);
    };
    window.addEventListener('gate-request', handler);
    return () => window.removeEventListener('gate-request', handler);
  }, []);

  function respond(approved: boolean) {
    if (!request) return;
    kernel.confirmGate(request.skillId, request.capName, approved);
    setOpen(false);
    setRequest(null);
  }

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) respond(false); }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" className="text-yellow-500">
              <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
              <line x1="12" y1="9" x2="12" y2="13" />
              <line x1="12" y1="17" x2="12.01" y2="17" />
            </svg>
            权限请求
          </DialogTitle>
          <DialogDescription className="pt-2 space-y-2">
            <div className="flex items-center gap-2 text-sm">
              <span className="font-medium text-foreground">{request?.skillId}</span>
              <span className="text-muted-foreground">请求使用能力：</span>
              <Badge variant="secondary" className="font-mono text-xs">
                {request?.capName}
              </Badge>
            </div>
            {request?.reason && (
              <p className="text-sm text-muted-foreground bg-muted rounded-md p-2.5">
                {request.reason}
              </p>
            )}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter className="gap-2">
          <Button variant="outline" onClick={() => respond(false)}>
            拒绝
          </Button>
          <Button onClick={() => respond(true)}>
            允许
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
