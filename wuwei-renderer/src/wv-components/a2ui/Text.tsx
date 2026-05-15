import { createComponentImplementation } from '@a2ui/react/v0_9';
import { TextApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { useMarkdownRenderer } from '@a2ui/react/v0_9';
import { useState, useEffect } from 'react';

function TextComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const renderer = useMarkdownRenderer();
  const [mdHtml, setMdHtml] = useState<string | null>(null);

  const rawText = typeof props.text === 'string' ? props.text : String(props.text ?? '');
  const variant = props.variant as string | undefined;

  let markdown = rawText;
  switch (variant) {
    case 'h1': markdown = `# ${rawText}`; break;
    case 'h2': markdown = `## ${rawText}`; break;
    case 'h3': markdown = `### ${rawText}`; break;
    case 'h4': markdown = `#### ${rawText}`; break;
    case 'h5': markdown = `##### ${rawText}`; break;
    case 'caption': markdown = `*${rawText}*`; break;
  }

  useEffect(() => {
    if (renderer) {
      renderer(markdown, undefined).then(setMdHtml).catch(() => setMdHtml(null));
    }
  }, [markdown, renderer]);

  const style: React.CSSProperties = {
    boxSizing: 'border-box',
    color: variant === 'caption' ? 'var(--a2ui-text-caption-color, #8b949e)' : undefined,
    fontSize: variant === 'caption' ? 'var(--a2ui-font-size-xs, 11px)' : undefined,
  };

  if (variant === 'caption') {
    return <span style={style} dangerouslySetInnerHTML={mdHtml ? { __html: mdHtml } : undefined}>{!mdHtml ? markdown : undefined}</span>;
  }
  return <div style={style} dangerouslySetInnerHTML={mdHtml ? { __html: mdHtml } : undefined}>{!mdHtml ? markdown : undefined}</div>;
}

export const WvA2uiText = createComponentImplementation(TextApi as never, TextComponent as never);
