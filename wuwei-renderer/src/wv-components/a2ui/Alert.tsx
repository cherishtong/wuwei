import { createComponentImplementation } from '@a2ui/react/v0_9';
import { z } from 'zod';
import { Alert as ShadcnAlert, AlertTitle, AlertDescription } from '@/wv-components/ui/alert';
import { useMarkdownRenderer } from '@a2ui/react/v0_9';
import { useState, useEffect } from 'react';

const DynamicStringSchema = z.union([
  z.string(),
  z.object({ path: z.string() }),
]);

export const AlertApi = {
  name: 'Alert',
  schema: z.object({
    title: DynamicStringSchema.describe('Alert title (supports Markdown).'),
    description: DynamicStringSchema.optional().describe('Alert description body (supports Markdown).'),
    variant: z.enum(['default', 'destructive']).default('default').optional().describe('Alert visual variant.'),
  }).strict(),
};

function MdSpan({ text }: { text: string }) {
  const renderer = useMarkdownRenderer();
  const [html, setHtml] = useState<string | null>(null);
  useEffect(() => {
    if (renderer) { renderer(text, undefined).then(setHtml).catch(() => setHtml(null)); }
  }, [text, renderer]);
  return html ? <span dangerouslySetInnerHTML={{ __html: html }} /> : <>{text}</>;
}

function AlertComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string) => React.ReactNode; context: unknown }) {
  const title = typeof props.title === 'string' ? props.title : String(props.title ?? '');
  const description = typeof props.description === 'string' ? props.description : undefined;
  const variant = (props.variant === 'destructive' ? 'destructive' : 'default') as 'default' | 'destructive';
  return (
    <ShadcnAlert variant={variant}>
      <AlertTitle><MdSpan text={title} /></AlertTitle>
      {description ? <AlertDescription><MdSpan text={description} /></AlertDescription> : null}
    </ShadcnAlert>
  );
}

export const WvA2uiAlert = createComponentImplementation(AlertApi as never, AlertComponent as never);
