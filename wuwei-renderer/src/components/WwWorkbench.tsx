import { useState, useEffect, useMemo } from 'react';
import { ScrollArea } from '@/wv-components/ui/scroll-area';
import { PrismLight as SyntaxHighlighter } from 'react-syntax-highlighter';
import jsonLang from 'react-syntax-highlighter/dist/esm/languages/prism/json';
import jsLang from 'react-syntax-highlighter/dist/esm/languages/prism/javascript';
import markdownLang from 'react-syntax-highlighter/dist/esm/languages/prism/markdown';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Skeleton } from '@/wv-components/ui/skeleton';
import { FileCode, File, FileText, FileJson, Database, ChevronRight, ChevronDown, Folder, FolderOpen } from 'lucide-react';

SyntaxHighlighter.registerLanguage('json', jsonLang);
SyntaxHighlighter.registerLanguage('javascript', jsLang);
SyntaxHighlighter.registerLanguage('markdown', markdownLang);

interface FileEntry {
  path: string;
  size: number;
  content?: string;
}
interface SkillSource {
  skillId: string;
  files: FileEntry[];
}

const LANG_MAP: Record<string, string> = {
  js: 'javascript', json: 'json', md: 'markdown',
  css: 'css', html: 'html', xml: 'xml',
  yaml: 'yaml', yml: 'yaml', toml: 'toml',
};
function detectLang(path: string): string { return LANG_MAP[path.split('.').pop()?.toLowerCase() || ''] || 'text'; }
function isPreviewable(path: string): boolean { return detectLang(path) !== 'text'; }
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatContent(path: string, content: string): string {
  if (path.endsWith('.json')) { try { return JSON.stringify(JSON.parse(content), null, 2); } catch { return content; } }
  return content;
}
function fileIcon(path: string) {
  const n = path.toLowerCase();
  if (n.endsWith('.js')) return <FileCode size={14} className="text-yellow-500 shrink-0" />;
  if (n.endsWith('.json')) return <FileJson size={14} className="text-blue-500 shrink-0" />;
  if (n.endsWith('.md')) return <FileText size={14} className="text-cyan-500 shrink-0" />;
  if (n.endsWith('.db') || n.endsWith('.db-shm') || n.endsWith('.db-wal')) return <Database size={14} className="text-orange-500 shrink-0" />;
  return <File size={14} className="text-muted-foreground shrink-0" />;
}

// Tree node structure
interface TreeNode {
  name: string;
  path: string;  // full relative path for files, directory path for folders
  isFolder: boolean;
  children: TreeNode[];
  file?: FileEntry;
}

function buildTree(files: FileEntry[]): TreeNode[] {
  const root: TreeNode = { name: '', path: '', isFolder: true, children: [] };
  for (const f of files) {
    const parts = f.path.split('/');
    let node = root;
    for (let i = 0; i < parts.length; i++) {
      const isLast = i === parts.length - 1;
      const name = parts[i];
      const fullPath = parts.slice(0, i + 1).join('/');
      let child = node.children.find(c => c.name === name);
      if (!child) {
        child = { name, path: fullPath, isFolder: !isLast, children: [] };
        if (isLast) child.file = f;
        node.children.push(child);
      }
      node = child;
    }
  }
  // Sort: folders first, then alphabetical
  const sortNode = (n: TreeNode) => {
    n.children.sort((a, b) => {
      if (a.isFolder !== b.isFolder) return a.isFolder ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
    n.children.forEach(sortNode);
  };
  sortNode(root);
  return root.children;
}

function TreeView({ nodes, activeFile, onSelect, depth }: { nodes: TreeNode[]; activeFile: string | null; onSelect: (path: string) => void; depth: number }) {
  return (
    <>
      {nodes.map(n => (
        <TreeNodeItem key={n.path} node={n} activeFile={activeFile} onSelect={onSelect} depth={depth} />
      ))}
    </>
  );
}

function TreeNodeItem({ node, activeFile, onSelect, depth }: { node: TreeNode; activeFile: string | null; onSelect: (path: string) => void; depth: number }) {
  const [open, setOpen] = useState(true);
  const isActive = node.file && node.path === activeFile;

  if (node.isFolder) {
    return (
      <div>
        <button
          className="w-full text-left px-2 py-1 flex items-center gap-1 text-xs text-muted-foreground hover:bg-accent transition-colors"
          style={{ paddingLeft: `${8 + depth * 12}px` }}
          onClick={() => setOpen(!open)}
        >
          {open ? <ChevronDown size={12} className="shrink-0" /> : <ChevronRight size={12} className="shrink-0" />}
          {open ? <FolderOpen size={14} className="shrink-0 text-amber-500" /> : <Folder size={14} className="shrink-0 text-amber-500" />}
          <span className="truncate">{node.name}</span>
        </button>
        {open && <TreeView nodes={node.children} activeFile={activeFile} onSelect={onSelect} depth={depth + 1} />}
      </div>
    );
  }

  return (
    <button
      className={`w-full text-left px-2 py-1 flex items-center gap-2 text-xs hover:bg-accent transition-colors ${isActive ? 'bg-accent' : ''}`}
      style={{ paddingLeft: `${8 + depth * 12}px` }}
      onClick={() => node.file && onSelect(node.path)}
    >
      {fileIcon(node.name)}
      <span className="truncate flex-1">{node.name}</span>
      {node.file && <span className="text-[10px] text-muted-foreground shrink-0">{formatSize(node.file.size)}</span>}
    </button>
  );
}

interface WwWorkbenchProps { onClose: () => void; }
export function WwWorkbench({ onClose }: WwWorkbenchProps) {
  const [source, setSource] = useState<SkillSource | null>(null);
  const [activeFile, setActiveFile] = useState<string | null>(null);

  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail as SkillSource;
      setSource(detail);
      const first = detail.files?.find(f => isPreviewable(f.path));
      setActiveFile(first?.path || detail.files?.[0]?.path || null);
    };
    window.addEventListener('skill-source', handler);
    return () => window.removeEventListener('skill-source', handler);
  }, []);

  const tree = useMemo(() => source ? buildTree(source.files) : [], [source]);
  const activeEntry = source?.files?.find(f => f.path === activeFile);
  const previewable = activeEntry ? isPreviewable(activeEntry.path) : false;

  if (!source) {
    return (
      <div className="flex h-full animate-fade-in">
        <div className="w-64 border-r flex flex-col flex-shrink-0 p-3 gap-2">
          {Array.from({ length: 10 }).map((_, i) => (
            <Skeleton key={i} className="h-5 w-full" style={{ width: `${70 - i * 3}%` }} />
          ))}
        </div>
        <div className="flex-1 p-4">
          <Skeleton className="h-full w-full rounded-lg" />
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full">
      {/* Left: file tree */}
      <div className="w-64 border-r flex flex-col flex-shrink-0">
        <div className="flex items-center px-3 py-2 border-b flex-shrink-0">
          <span className="text-xs font-semibold font-mono truncate">{source.skillId}</span>
        </div>
        <ScrollArea className="flex-1">
          <div className="py-1">
            <TreeView nodes={tree} activeFile={activeFile} onSelect={setActiveFile} depth={0} />
          </div>
        </ScrollArea>
      </div>

      {/* Right: preview */}
      <div className="flex-1 flex flex-col min-w-0">
        <div className="flex items-center gap-2 px-4 py-2 border-b flex-shrink-0">
          <span className="text-xs text-muted-foreground truncate">{activeFile || '选择文件'}</span>
        </div>
        <div className="flex-1 min-h-0 p-4">
          {activeEntry && previewable && activeEntry.content != null ? (
            <ScrollArea className="h-full">
              <SyntaxHighlighter language={detectLang(activeEntry.path)} style={oneDark}
                customStyle={{ margin: 0, borderRadius: '0.5rem', fontSize: '0.8125rem', lineHeight: '1.6' }}
                showLineNumbers>{formatContent(activeEntry.path, activeEntry.content)}</SyntaxHighlighter>
            </ScrollArea>
          ) : activeEntry ? (
            <div className="flex flex-col items-center justify-center h-full text-sm text-muted-foreground gap-3">
              {fileIcon(activeEntry.path)} <p>{activeEntry.path}</p>
              <p className="text-xs">{formatSize(activeEntry.size)} — 不支持预览</p>
            </div>
          ) : (
            <div className="flex items-center justify-center h-full text-sm text-muted-foreground">选择左侧文件查看内容</div>
          )}
        </div>
      </div>
    </div>
  );
}
