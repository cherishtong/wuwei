import { useState, useCallback } from 'react';
import {
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSub,
  SidebarMenuSubButton,
  SidebarMenuSubItem,
  SidebarSeparator,
} from '@/wv-components/ui/sidebar';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/wv-components/ui/collapsible';
import { ChevronRight } from 'lucide-react';
import { kernel } from '../kernel';

interface MenuItem {
  label: string;
  file?: string;
  children?: MenuItem[];
}

interface SidebarConfig {
  home?: { label: string; file: string };
  menu?: MenuItem[];
}

interface WwBlogSidebarProps {
  skillId: string;
  sidebarConfig: SidebarConfig | null;
  activeFile: string | null;
  onNavigate: (file: string) => void;
}

export function WwBlogSidebar({ skillId, sidebarConfig, activeFile, onNavigate }: WwBlogSidebarProps) {
  const [openBranches, setOpenBranches] = useState<Set<string>>(new Set());

  const toggleBranch = useCallback((key: string) => {
    setOpenBranches((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  function handleNav(file: string) {
    onNavigate(file);
    kernel.handleEvent(skillId, 'nav', { file });
  }

  function renderMenuItems(items: MenuItem[], level: number, parentKey: string): React.ReactNode {
    return items.map((item, i) => {
      const key = `${parentKey}-${i}`;
      const hasChildren = item.children && item.children.length > 0;

      if (hasChildren) {
        const isOpen = openBranches.has(key);
        return (
          <Collapsible key={key} open={isOpen} onOpenChange={() => toggleBranch(key)}>
            <SidebarMenuItem>
              <CollapsibleTrigger asChild>
                <SidebarMenuButton>
                  <ChevronRight
                    className={`size-4 shrink-0 transition-transform ${isOpen ? 'rotate-90' : ''}`}
                  />
                  <span>{item.label}</span>
                </SidebarMenuButton>
              </CollapsibleTrigger>
              <CollapsibleContent>
                <SidebarMenuSub>
                  {renderMenuItems(item.children!, level + 1, key)}
                </SidebarMenuSub>
              </CollapsibleContent>
            </SidebarMenuItem>
          </Collapsible>
        );
      }

      // Leaf: clickable article
      if (item.file) {
        const isActive = activeFile === item.file;
        return level === 0 ? (
          <SidebarMenuItem key={key}>
            <SidebarMenuButton isActive={isActive} onClick={() => handleNav(item.file!)}>
              {item.label}
            </SidebarMenuButton>
          </SidebarMenuItem>
        ) : (
          <SidebarMenuSubItem key={key}>
            <SidebarMenuSubButton isActive={isActive} onClick={() => handleNav(item.file!)}>
              {item.label}
            </SidebarMenuSubButton>
          </SidebarMenuSubItem>
        );
      }

      return null;
    });
  }

  if (!sidebarConfig) {
    return (
      <div className="flex flex-col">
        <div className="px-3 py-2 text-sm font-semibold shrink-0">博客导航</div>
        <div className="flex-1 flex items-center justify-center">
          <span className="text-xs text-muted-foreground">暂无菜单配置</span>
        </div>
      </div>
    );
  }

  const { home, menu } = sidebarConfig;

  return (
    <div className="flex flex-col">
      {/* Header */}
      <div className="px-3 py-2 text-sm font-semibold shrink-0">📖 文章导航</div>

      {/* Scrollable menu area */}
      <div className="flex-1 overflow-y-auto min-h-0">
        <SidebarMenu>
          {/* Home link */}
          {home && (
            <>
              <SidebarMenuItem>
                <SidebarMenuButton
                  isActive={activeFile === home.file}
                  onClick={() => handleNav(home.file)}
                >
                  🏠 {home.label}
                </SidebarMenuButton>
              </SidebarMenuItem>
              {menu && menu.length > 0 && <SidebarSeparator />}
            </>
          )}

          {/* Menu tree */}
          {menu && menu.length > 0 && renderMenuItems(menu, 0, 'menu')}
        </SidebarMenu>
      </div>
    </div>
  );
}
