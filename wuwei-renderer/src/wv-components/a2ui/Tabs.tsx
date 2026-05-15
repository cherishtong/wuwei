import { useState } from 'react';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { TabsApi } from '@a2ui/web_core/v0_9/basic_catalog';
import { Tabs as ShadcnTabs, TabsList, TabsTrigger, TabsContent } from '@/wv-components/ui/tabs';

function TabsComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const tabs = (Array.isArray(props.tabs) ? props.tabs : []) as Array<{ title: string; child: string }>;
  const [activeTab, setActiveTab] = useState('0');

  if (!tabs.length) return null;

  return (
    <ShadcnTabs value={activeTab} onValueChange={setActiveTab}>
      <TabsList>
        {tabs.map((tab, i) => (
          <TabsTrigger key={i} value={String(i)}>{tab.title}</TabsTrigger>
        ))}
      </TabsList>
      {tabs.map((tab, i) => (
        <TabsContent key={i} value={String(i)}>
          {tab.child ? buildChild(tab.child) : null}
        </TabsContent>
      ))}
    </ShadcnTabs>
  );
}

export const WvA2uiTabs = createComponentImplementation(TabsApi as never, TabsComponent as never);
