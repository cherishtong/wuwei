import { createComponentImplementation, useMarkdownRenderer } from '@a2ui/react/v0_9';
import { TextApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { useState, useEffect } from 'react';

function TextComponent({ props, context }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: { componentModel: { properties: Record<string, unknown> } } }) {
  const renderer = useMarkdownRenderer();
  const [mdHtml, setMdHtml] = useState<string | null>(null);

  const rawText = typeof props.text === 'string' ? props.text : String(props.text ?? '');
  const variant = (context.componentModel.properties.variant as string) || 'body';

  // Headings: inline styles (avoids Tailwind preflight + MarkdownContext nesting issues)
  if (variant === 'h1') {
    return <div style={{ fontSize: '2.25rem', fontWeight: 700, lineHeight: '2.5rem', letterSpacing: '-0.025em' }}>{rawText}</div>;
  }
  if (variant === 'h2') {
    return <div style={{ fontSize: '1.875rem', fontWeight: 600, lineHeight: '2.25rem', letterSpacing: '-0.025em' }}>{rawText}</div>;
  }
  if (variant === 'h3') {
    return <div style={{ fontSize: '1.5rem', fontWeight: 600, lineHeight: '2rem', letterSpacing: '-0.025em' }}>{rawText}</div>;
  }
  if (variant === 'h4') {
    return <div style={{ fontSize: '1.25rem', fontWeight: 600, lineHeight: '1.75rem', letterSpacing: '-0.025em' }}>{rawText}</div>;
  }
  if (variant === 'h5') {
    return <div style={{ fontSize: '1.125rem', fontWeight: 500, lineHeight: '1.75rem' }}>{rawText}</div>;
  }

  // Body / caption: use markdown renderer for rich text (bold, italic, code, tables, etc.)
  useEffect(() => {
    if (renderer) {
      renderer(rawText, undefined).then(setMdHtml).catch(() => setMdHtml(null));
    }
  }, [rawText, renderer]);

  const isCaption = variant === 'caption';
  const twClass = isCaption ? 'text-xs text-muted-foreground' : 'text-base';

  if (isCaption) {
    return (
      <span className={twClass}
        dangerouslySetInnerHTML={mdHtml ? { __html: mdHtml } : undefined}>
        {!mdHtml ? rawText : undefined}
      </span>
    );
  }
  return (
    <div className={twClass}
      dangerouslySetInnerHTML={mdHtml ? { __html: mdHtml } : undefined}>
      {!mdHtml ? rawText : undefined}
    </div>
  );
}

export const WvA2uiText = createComponentImplementation(TextApi as never, TextComponent as never);
