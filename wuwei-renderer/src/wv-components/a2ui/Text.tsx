import { createComponentImplementation, useMarkdownRenderer } from '@a2ui/react/v0_9';
import { TextApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { useState, useEffect } from 'react';

function handleVariant(text: string, variant?: string): string {
  switch (variant) {
    case 'h1': return `# ${text}`;
    case 'h2': return `## ${text}`;
    case 'h3': return `### ${text}`;
    case 'h4': return `#### ${text}`;
    case 'h5': return `##### ${text}`;
    case 'caption': return `*${text}*`;
    default: return text;
  }
}

function useMarkdown(text: string): string | null {
  const renderer = useMarkdownRenderer();
  const [html, setHtml] = useState<string | null>(null);

  useEffect(() => {
    if (!renderer) {
      setHtml(null);
      return;
    }
    let active = true;
    renderer(text, undefined)
      .then((result) => { if (active) setHtml(result); })
      .catch(() => { if (active) setHtml(null); });
    return () => { active = false; };
  }, [text, renderer]);

  return html;
}

function TextComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const text = typeof props.text === 'string' ? props.text : String(props.text ?? '');
  const variant = (props.variant as string) || 'body';
  const markdownText = handleVariant(text, variant);
  const renderedHtml = useMarkdown(markdownText);

  if (variant === 'h1') {
    return <div style={{ fontSize: '2.25rem', fontWeight: 700, lineHeight: '2.5rem', letterSpacing: '-0.025em' }}>{text}</div>;
  }
  if (variant === 'h2') {
    return <div style={{ fontSize: '1.875rem', fontWeight: 600, lineHeight: '2.25rem', letterSpacing: '-0.025em' }}>{text}</div>;
  }
  if (variant === 'h3') {
    return <div style={{ fontSize: '1.5rem', fontWeight: 600, lineHeight: '2rem', letterSpacing: '-0.025em' }}>{text}</div>;
  }
  if (variant === 'h4') {
    return <div style={{ fontSize: '1.25rem', fontWeight: 600, lineHeight: '1.75rem', letterSpacing: '-0.025em' }}>{text}</div>;
  }
  if (variant === 'h5') {
    return <div style={{ fontSize: '1.125rem', fontWeight: 500, lineHeight: '1.75rem' }}>{text}</div>;
  }

  // Use <span> for body/caption — valid inside <button>, avoids block-in-inline issues
  const isCaption = variant === 'caption';
  const twClass = isCaption ? 'text-xs text-muted-foreground' : 'text-sm';

  if (renderedHtml) {
    // markdown-it renders bare '-' and '+' as <ul><li></li></ul> (empty bullet
    // list). Strip tags and whitespace to check if there's visible text content.
    const visibleText = renderedHtml.replace(/<[^>]*>/g, '').replace(/\s/g, '');
    if (visibleText) {
      return <span className={twClass} dangerouslySetInnerHTML={{ __html: renderedHtml }} />;
    }
  }
  return <span className={twClass}>{markdownText}</span>;
}

export const WvA2uiText = createComponentImplementation(TextApi as never, TextComponent as never);
