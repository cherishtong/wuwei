import { createComponentImplementation } from '@a2ui/react/v0_9';
import { z } from 'zod';
import { useRef, useEffect, useCallback } from 'react';
import { useResizeDetector } from 'react-resize-detector';

export const CanvasApi = {
  name: 'Canvas',
  schema: z
    .object({
      width: z.number().optional().describe('Canvas width in pixels (omit for auto-fill)'),
      height: z.number().optional().describe('Canvas height in pixels (omit for auto-fill)'),
      weight: z.number().optional().describe('Flex grow weight when inside Row/Column'),
    })
    .strict(),
};

interface DrawCommand {
  type: 'rect' | 'text' | 'arrow' | 'line' | 'roundRect';
  x: number;
  y: number;
  w?: number;
  h?: number;
  r?: number;
  x2?: number;
  y2?: number;
  fill?: string;
  stroke?: string;
  lineWidth?: number;
  text?: string;
  font?: string;
  align?: CanvasTextAlign;
  baseline?: CanvasTextBaseline;
}

function drawCommands(ctx: CanvasRenderingContext2D, commands: DrawCommand[]) {
  ctx.clearRect(0, 0, ctx.canvas.width, ctx.canvas.height);
  for (const cmd of commands) {
    switch (cmd.type) {
      case 'rect': {
        if (cmd.fill) {
          ctx.fillStyle = cmd.fill;
          ctx.fillRect(cmd.x, cmd.y, cmd.w ?? 0, cmd.h ?? 0);
        }
        if (cmd.stroke) {
          ctx.strokeStyle = cmd.stroke;
          ctx.lineWidth = cmd.lineWidth ?? 1;
          ctx.strokeRect(cmd.x, cmd.y, cmd.w ?? 0, cmd.h ?? 0);
        }
        break;
      }
      case 'roundRect': {
        ctx.beginPath();
        const r = cmd.r ?? 8;
        const w = cmd.w ?? 0;
        const h = cmd.h ?? 0;
        ctx.moveTo(cmd.x + r, cmd.y);
        ctx.lineTo(cmd.x + w - r, cmd.y);
        ctx.quadraticCurveTo(cmd.x + w, cmd.y, cmd.x + w, cmd.y + r);
        ctx.lineTo(cmd.x + w, cmd.y + h - r);
        ctx.quadraticCurveTo(cmd.x + w, cmd.y + h, cmd.x + w - r, cmd.y + h);
        ctx.lineTo(cmd.x + r, cmd.y + h);
        ctx.quadraticCurveTo(cmd.x, cmd.y + h, cmd.x, cmd.y + h - r);
        ctx.lineTo(cmd.x, cmd.y + r);
        ctx.quadraticCurveTo(cmd.x, cmd.y, cmd.x + r, cmd.y);
        ctx.closePath();
        if (cmd.fill) { ctx.fillStyle = cmd.fill; ctx.fill(); }
        if (cmd.stroke) { ctx.strokeStyle = cmd.stroke; ctx.lineWidth = cmd.lineWidth ?? 1; ctx.stroke(); }
        break;
      }
      case 'text': {
        if (cmd.font) ctx.font = cmd.font;
        if (cmd.fill) ctx.fillStyle = cmd.fill;
        if (cmd.align) ctx.textAlign = cmd.align;
        if (cmd.baseline) ctx.textBaseline = cmd.baseline;
        ctx.fillText(cmd.text ?? '', cmd.x, cmd.y);
        break;
      }
      case 'line': {
        ctx.beginPath();
        ctx.strokeStyle = cmd.stroke ?? '#000';
        ctx.lineWidth = cmd.lineWidth ?? 1;
        ctx.moveTo(cmd.x, cmd.y);
        ctx.lineTo(cmd.x2 ?? cmd.x, cmd.y2 ?? cmd.y);
        ctx.stroke();
        break;
      }
      case 'arrow': {
        const x1 = cmd.x;
        const y1 = cmd.y;
        const x2 = cmd.x2 ?? cmd.x;
        const y2 = cmd.y2 ?? cmd.y;
        const headLen = 10;
        const angle = Math.atan2(y2 - y1, x2 - x1);

        ctx.beginPath();
        ctx.strokeStyle = cmd.stroke ?? '#000';
        ctx.lineWidth = cmd.lineWidth ?? 1.5;
        ctx.moveTo(x1, y1);
        ctx.lineTo(x2, y2);
        ctx.stroke();

        ctx.beginPath();
        ctx.fillStyle = cmd.stroke ?? '#000';
        ctx.moveTo(x2, y2);
        ctx.lineTo(
          x2 - headLen * Math.cos(angle - Math.PI / 6),
          y2 - headLen * Math.sin(angle - Math.PI / 6)
        );
        ctx.lineTo(
          x2 - headLen * Math.cos(angle + Math.PI / 6),
          y2 - headLen * Math.sin(angle + Math.PI / 6)
        );
        ctx.closePath();
        ctx.fill();
        break;
      }
    }
  }
}

function CanvasComponent({ props, context }: {
  props: Record<string, unknown>;
  buildChild: (id: string, basePath?: string) => React.ReactNode;
  context: unknown;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const fixedWidth = props.width as number | undefined;
  const fixedHeight = props.height as number | undefined;
  const isAutoSize = !fixedWidth || !fixedHeight;

  const componentId = (context as { componentModel?: { id?: string } })?.componentModel?.id;

  // Bridge resize events from react-resize-detector to Three.js capability
  const onResize = useCallback((payload: { width?: number; height?: number }) => {
    const w = payload?.width;
    const h = payload?.height;
    if (w != null && h != null && w > 0 && h > 0 && componentId) {
      const handler = (window as unknown as Record<string, unknown>)[`__wuwei_threejs_resize_${componentId}`];
      if (typeof handler === 'function') handler(w, h);
    }
  }, [componentId]);

  const { ref: _resizeRef } = useResizeDetector({
    targetRef: wrapperRef,
    onResize: onResize as never,
    handleWidth: true,
    handleHeight: true,
    refreshMode: 'debounce',
    refreshRate: 100,
  });

  // Expose 2D canvas draw function
  useEffect(() => {
    const el = canvasRef.current;
    if (!el) return;
    if (!componentId) return;

    (window as unknown as Record<string, unknown>)[`__wuwei_canvas_${componentId}`] = {
      el,
      draw: (commands: DrawCommand[]) => {
        const ctx = el.getContext('2d');
        if (!ctx) return;
        const rect = el.getBoundingClientRect();
        const w = Math.round(rect.width);
        const h = Math.round(rect.height);
        if (w > 0 && h > 0 && (el.width !== w || el.height !== h)) {
          el.width = w;
          el.height = h;
        }
        drawCommands(ctx, commands);
      },
    };

    return () => {
      delete (window as unknown as Record<string, unknown>)[`__wuwei_canvas_${componentId}`];
    };
  }, [context]);

  const weight = typeof props.weight === 'number' ? props.weight : undefined;

  // Wrapper fills parent via flex; canvas fills wrapper via absolute positioning
  const wrapperStyle: React.CSSProperties = isAutoSize
    ? {
        flex: weight ? `${weight} ${weight} 0%` : '1 1 0%',
        minHeight: 0,
        minWidth: 0,
        position: 'relative',
        width: '100%',
        borderRadius: '0.375rem',
        border: '1px solid hsl(var(--border))',
        backgroundColor: 'hsl(var(--background))',
        overflow: 'hidden',
      }
    : {
        display: 'inline-block',
        borderRadius: '0.375rem',
        border: '1px solid hsl(var(--border))',
        backgroundColor: 'hsl(var(--background))',
        width: `${fixedWidth}px`,
        height: `${fixedHeight}px`,
      };

  const canvasStyle: React.CSSProperties = isAutoSize
    ? { position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block' }
    : { width: '100%', height: '100%', display: 'block' };

  return (
    <div ref={wrapperRef} style={wrapperStyle}>
      <canvas id={componentId} ref={canvasRef} style={canvasStyle} />
    </div>
  );
}

export const WvA2uiCanvas = createComponentImplementation(CanvasApi as never, CanvasComponent as never);
