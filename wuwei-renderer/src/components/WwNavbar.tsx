import { useTheme } from '../contexts/ThemeContext';

export type NavTab = 'home' | 'skills' | 'system';

interface NavbarProps {
  activeTab: NavTab;
  onTabChange: (tab: NavTab) => void;
}

const tabs: { id: NavTab; label: string }[] = [
  { id: 'home', label: '会话' },
  { id: 'skills', label: '技能' },
  { id: 'system', label: '系统' },
];

export function WwNavbar({ activeTab, onTabChange }: NavbarProps) {
  const { resolved } = useTheme();
  const isDark = resolved === 'dark';

  return (
    <nav
      className="flex items-center flex-shrink-0 select-none border-b z-20"
      style={{
        height: '40px',
        background: isDark ? 'rgba(9,9,11,0.6)' : 'rgba(249,246,241,0.6)',
        backdropFilter: 'blur(12px)',
        WebkitBackdropFilter: 'blur(12px)',
        borderColor: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.05)',
      }}
    >
      <div className="flex items-center h-full px-4 gap-1">
        {tabs.map((tab) => {
          const active = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => onTabChange(tab.id)}
              className="relative px-4 h-full flex items-center text-sm font-medium transition-colors"
              style={{
                color: active
                  ? (isDark ? '#fff' : '#1a1a1b')
                  : (isDark ? 'rgba(255,255,255,0.4)' : '#9ca3af'),
              }}
            >
              {tab.label}
              {active && (
                <span
                  className="absolute bottom-0 left-1/2 -translate-x-1/2 w-6 h-0.5 rounded-full"
                  style={{ background: isDark ? '#fff' : '#1a1a1b' }}
                />
              )}
            </button>
          );
        })}
      </div>
    </nav>
  );
}
