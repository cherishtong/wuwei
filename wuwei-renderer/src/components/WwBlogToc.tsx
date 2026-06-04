import { useState, useEffect, useCallback, useRef } from 'react';

interface TocItem {
  id: string;
  text: string;
  level: number;
}

/**
 * Table of contents for markdown blog content.
 * Extracts headings from the .md-content DOM and tracks active heading.
 */
export function WwBlogToc() {
  const [items, setItems] = useState<TocItem[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const scanTimer = useRef<ReturnType<typeof setTimeout>>();

  // Scan the content area for headings
  useEffect(() => {
    const scan = () => {
      // Debounce — MutationObserver fires rapidly
      if (scanTimer.current) clearTimeout(scanTimer.current);
      scanTimer.current = setTimeout(() => {
        const contentEl = document.querySelector('.md-content');
        if (!contentEl) { setItems([]); return; }

        const headings = contentEl.querySelectorAll('h1, h2, h3');
        const toc: TocItem[] = [];
        headings.forEach((h, i) => {
          const id = h.id || `md-h-${i}`;
          if (!h.id) h.id = id;
          toc.push({
            id,
            text: h.textContent || '',
            level: parseInt(h.tagName.charAt(1)),
          });
        });
        setItems(toc);
      }, 150);
    };

    scan();

    const observer = new MutationObserver(() => scan());
    const blogObserver = new MutationObserver(() => {
      const el = document.querySelector('.md-content');
      if (el) { scan(); observer.observe(el, { childList: true, subtree: true }); }
    });
    blogObserver.observe(document.body, { childList: true, subtree: true });

    return () => {
      observer.disconnect();
      blogObserver.disconnect();
      if (scanTimer.current) clearTimeout(scanTimer.current);
    };
  }, []);

  // Track which heading is currently visible
  useEffect(() => {
    if (items.length === 0) return;

    const observer = new IntersectionObserver(
      (entries) => {
        // Find the first visible heading (reading from top)
        const visible = entries
          .filter((e) => e.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
        if (visible.length > 0) {
          setActiveId(visible[0].target.id);
        }
      },
      { rootMargin: '-10% 0px -70% 0px', threshold: 0 }
    );

    items.forEach((item) => {
      const el = document.getElementById(item.id);
      if (el) observer.observe(el);
    });

    return () => observer.disconnect();
  }, [items]);

  const scrollTo = useCallback((id: string) => {
    const el = document.getElementById(id);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, []);

  if (items.length === 0) return null;

  return (
    <>
      <div className="px-3 py-2 text-xs font-semibold shrink-0 text-sidebar-foreground/70">
        目录
      </div>
      <div className="flex-1 overflow-y-auto min-h-0 px-2 py-1">
        {items.map((item) => (
          <button
            key={item.id}
            onClick={() => scrollTo(item.id)}
            className={`block w-full text-left text-xs py-1 px-2 rounded truncate transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground ${
              activeId === item.id
                ? 'text-sidebar-accent-foreground font-medium bg-sidebar-accent/50'
                : 'text-sidebar-foreground/60'
            }`}
            style={{ paddingLeft: `${8 + (item.level - 1) * 12}px` }}
          >
            {item.text}
          </button>
        ))}
      </div>
    </>
  );
}
