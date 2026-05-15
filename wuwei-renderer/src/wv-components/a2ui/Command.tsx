import { useState } from 'react';
import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Command as ShadcnCommand, CommandInput, CommandList, CommandGroup, CommandItem, CommandEmpty } from '@/wv-components/ui/command';

const CommandApi = {
  name: 'Command',
  schema: z.object({
    items: z.array(z.object({
      label: z.string(),
      value: z.string(),
      group: z.string().optional().describe('Optional group label for grouping items.'),
    })).describe('The command items.'),
    placeholder: z.string().default('Type a command or search...').optional().describe('Placeholder text in the search input.'),
  }).strict(),
};

function CommandComponent({ props }: { props: Record<string, unknown> & { setValue?: (val: string) => void }; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const items = (Array.isArray(props.items) ? props.items : []) as Array<{ label: string; value: string; group?: string }>;
  const [filter, setFilter] = useState('');

  const filtered = filter
    ? items.filter((item) => item.label.toLowerCase().includes(filter.toLowerCase()))
    : items;

  const grouped = new Map<string, typeof items>();
  const ungrouped: typeof items = [];

  for (const item of filtered) {
    if (item.group) {
      if (!grouped.has(item.group)) grouped.set(item.group, []);
      grouped.get(item.group)!.push(item);
    } else {
      ungrouped.push(item);
    }
  }

  return (
    <ShadcnCommand className="rounded-lg border shadow-md">
      <CommandInput
        placeholder={(props.placeholder as string) || 'Type a command or search...'}
        value={filter}
        onValueChange={setFilter}
      />
      <CommandList>
        {filtered.length === 0 && <CommandEmpty>No results found.</CommandEmpty>}
        {ungrouped.length > 0 && (
          <CommandGroup>
            {ungrouped.map((item) => (
              <CommandItem
                key={item.value}
                onSelect={() => props.setValue?.(item.value)}
              >
                {item.label}
              </CommandItem>
            ))}
          </CommandGroup>
        )}
        {Array.from(grouped.entries()).map(([group, groupItems]) => (
          <CommandGroup key={group} heading={group}>
            {groupItems.map((item) => (
              <CommandItem
                key={item.value}
                onSelect={() => props.setValue?.(item.value)}
              >
                {item.label}
              </CommandItem>
            ))}
          </CommandGroup>
        ))}
      </CommandList>
    </ShadcnCommand>
  );
}

export const WvA2uiCommand = createComponentImplementation(CommandApi as never, CommandComponent as never);
