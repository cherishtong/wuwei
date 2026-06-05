import { createComponentImplementation, useMarkdownRenderer } from '@a2ui/react/v0_9';
import { TextApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { useState, useEffect, useMemo } from 'react';
import { PrismLight as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark, oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { useTheme } from '../../contexts/ThemeContext';
import js from 'react-syntax-highlighter/dist/esm/languages/prism/javascript';
import ts from 'react-syntax-highlighter/dist/esm/languages/prism/typescript';
import css from 'react-syntax-highlighter/dist/esm/languages/prism/css';
import json from 'react-syntax-highlighter/dist/esm/languages/prism/json';
import java from 'react-syntax-highlighter/dist/esm/languages/prism/java';
import python from 'react-syntax-highlighter/dist/esm/languages/prism/python';
import bash from 'react-syntax-highlighter/dist/esm/languages/prism/bash';
import xml from 'react-syntax-highlighter/dist/esm/languages/prism/xml-doc';
import yaml from 'react-syntax-highlighter/dist/esm/languages/prism/yaml';
import sql from 'react-syntax-highlighter/dist/esm/languages/prism/sql';
import rust from 'react-syntax-highlighter/dist/esm/languages/prism/rust';
import go from 'react-syntax-highlighter/dist/esm/languages/prism/go';

var LANG_MAP: Record<string, any> = {
  js: js, javascript: js, jsx: js, ts: ts, typescript: ts, tsx: ts,
  css: css, json: json, java: java, py: python, python: python,
  bash: bash, sh: bash, shell: bash, xml: xml, html: xml,
  yaml: yaml, yml: yaml, sql: sql, rust: rust, rs: rust, go: go,
};

function handleVariant(text: string, variant?: string): string {
  switch (variant) {
    case 'h1': return '# ' + text;
    case 'h2': return '## ' + text;
    case 'h3': return '### ' + text;
    case 'h4': return '#### ' + text;
    case 'h5': return '##### ' + text;
    case 'caption': return '*' + text + '*';
    default: return text;
  }
}

function unescapeHtml(s: string): string {
  return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&').replace(/&quot;/g, '"');
}

function TextComponent({ props }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const text = typeof props.text === 'string' ? props.text : String(props.text ?? '');
  const variant = (props.variant as string) || 'body';
  const markdownText = handleVariant(text, variant);
  const { resolved: theme } = useTheme();
  const codeStyle = theme === 'dark' ? oneDark : oneLight;

  // Pre-process: protect <video> and <iframe> from markdown escaping
  var mediaBlocks: string[] = [];
  var processedText = markdownText.replace(/<(video|iframe)\b[\s\S]*?<\/\1>/gi, function (match) {
    mediaBlocks.push(match);
    return 'MEDIABLOCK' + (mediaBlocks.length - 1) + 'END';
  });

  const renderer = useMarkdownRenderer();
  const [renderedHtml, setHtml] = useState<string | null>(null);
  useEffect(function () {
    if (!renderer) { setHtml(null); return; }
    var active = true;
    renderer(processedText, undefined).then(function (r) {
      if (!active) return;
      for (var i = 0; i < mediaBlocks.length; i++) {
        r = r.replace('MEDIABLOCK' + i + 'END', mediaBlocks[i]);
      }
      setHtml(r);
    }).catch(function () { if (active) setHtml(null); });
    return function () { active = false; };
  }, [markdownText, renderer]);

  var twClass = variant === 'caption' ? 'text-xs text-muted-foreground' : 'text-sm';

  // Split HTML into code blocks and regular content
  var codeBlocks = useMemo(function () {
    if (!renderedHtml) return null;
    var parts: { type: string; html?: string; lang?: string; code?: string }[] = [];
    var lastIdx = 0;
    var re = /<pre><code(?: class="language-(\w+)")?>([\s\S]*?)<\/code><\/pre>/g;
    var m;
    while ((m = re.exec(renderedHtml)) !== null) {
      if (m.index > lastIdx) parts.push({ type: 'html', html: renderedHtml.slice(lastIdx, m.index) });
      parts.push({ type: 'code', lang: m[1] || '', code: unescapeHtml(m[2]) });
      lastIdx = m.index + m[0].length;
    }
    if (lastIdx < renderedHtml.length) parts.push({ type: 'html', html: renderedHtml.slice(lastIdx) });
    return parts;
  }, [renderedHtml]);

  if (variant === 'h1') return <div style={{ fontSize: '2.25rem', fontWeight: 700 }}>{text}</div>;
  if (variant === 'h2') return <div style={{ fontSize: '1.875rem', fontWeight: 600 }}>{text}</div>;
  if (variant === 'h3') return <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{text}</div>;
  if (variant === 'h4') return <div style={{ fontSize: '1.25rem', fontWeight: 600 }}>{text}</div>;
  if (variant === 'h5') return <div style={{ fontSize: '1.125rem', fontWeight: 500 }}>{text}</div>;

  if (codeBlocks && codeBlocks.length > 0) {
    return (
      <div className={'md-content ' + twClass}>
        {codeBlocks.map(function (part, i) {
          if (part.type === 'code') {
            var langModule = LANG_MAP[part.lang || ''] || js;
            SyntaxHighlighter.registerLanguage(part.lang || 'text', langModule);
            return <SyntaxHighlighter key={i} language={part.lang || 'text'} style={codeStyle} customStyle={{ margin: '0.5em 0', borderRadius: '0.5rem', fontSize: '0.8125rem' }}>{part.code || ''}</SyntaxHighlighter>;
          }
          return <span key={i} dangerouslySetInnerHTML={{ __html: part.html || '' }} />;
        })}
      </div>
    );
  }

  if (renderedHtml) {
    var visible = renderedHtml.replace(/<[^>]*>/g, '').replace(/\s/g, '');
    if (visible) {
      return <div className={'md-content ' + twClass} dangerouslySetInnerHTML={{ __html: renderedHtml }} />;
    }
  }
  return <div className={twClass}>{markdownText}</div>;
}

export const WvA2uiText = createComponentImplementation(TextApi as never, TextComponent as never);
