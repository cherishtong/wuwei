import { type ReactNode } from 'react';
import { Group, Panel, Separator } from 'react-resizable-panels';

interface WwResizableLayoutProps {
  left: ReactNode;
  right: ReactNode;
  defaultLeftSize?: number;
  minLeftSize?: number;
  maxLeftSize?: number;
}

export function WwResizableLayout({
  left,
  right,
  defaultLeftSize = 22,
  minLeftSize = 15,
  maxLeftSize = 40,
}: WwResizableLayoutProps) {
  return (
    <div className="absolute inset-0">
      <Group
        id="skills-layout"
        orientation="horizontal"
        resizeTargetMinimumSize={{ fine: 20, coarse: 40 }}
      >
        <Panel
          id="skills-sidebar"
          defaultSize={String(defaultLeftSize)}
          minSize={String(minLeftSize)}
          maxSize={String(maxLeftSize)}
        >
          <div style={{ height: '100%', overflow: 'auto' }}>{left}</div>
        </Panel>
        <Separator
          style={{
            background: 'hsl(var(--border))',
            width: 4,
            cursor: 'col-resize',
          }}
        />
        <Panel
          id="skills-content"
          defaultSize={String(100 - defaultLeftSize)}
          minSize="0"
        >
          <div style={{ height: '100%', overflow: 'auto' }}>{right}</div>
        </Panel>
      </Group>
    </div>
  );
}
