import { useState } from 'react';
import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Accordion as ShadcnAccordion, AccordionItem, AccordionTrigger, AccordionContent } from '@/wv-components/ui/accordion';

const AccordionApi = {
  name: 'Accordion',
  schema: z.object({
    items: z.array(z.object({
      // New-style schema
      value: z.string().optional().describe('Unique value for this accordion item. Auto-generated from triggerText/title if omitted.'),
      triggerText: z.string().optional().describe('The trigger text.'),
      contentId: z.string().optional().describe('The ID of the content component.'),
      // Legacy aliases (prompt line 456 uses {title, content})
      title: z.string().optional().describe('Legacy alias for triggerText.'),
      content: z.string().optional().describe('Legacy alias for contentId.'),
    })).describe('The accordion items. Supports both new ({value,triggerText,contentId}) and legacy ({title,content}) formats.'),
    type: z.enum(['single', 'multiple']).default('single').optional().describe('Whether single or multiple items can be open.'),
  }).strict(),
};

/** Normalize an accordion item from either the old {title, content} or new {value, triggerText, contentId} format. */
function normalizeItem(raw: Record<string, unknown>, index: number): { value: string; triggerText: string; contentId: string } {
  const triggerText = (raw.triggerText as string) || (raw.title as string) || '';
  const contentId = (raw.contentId as string) || (raw.content as string) || '';
  const value = (raw.value as string) || triggerText.replace(/\s+/g, '-').toLowerCase() || `item-${index}`;
  return { value, triggerText, contentId };
}

function AccordionComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const rawItems = (Array.isArray(props.items) ? props.items : []) as Array<Record<string, unknown>>;
  const items = rawItems.map((item, i) => normalizeItem(item, i));
  const [value, setValue] = useState<string>('');

  if (!items.length) return null;

  return (
    <ShadcnAccordion
      type={(props.type as 'single' | 'multiple') || 'single'}
      value={value as never}
      onValueChange={setValue as never}
    >
      {items.map((item) => (
        <AccordionItem key={item.value} value={item.value}>
          <AccordionTrigger>{item.triggerText}</AccordionTrigger>
          <AccordionContent>
            {item.contentId ? buildChild(item.contentId) : null}
          </AccordionContent>
        </AccordionItem>
      ))}
    </ShadcnAccordion>
  );
}

export const WvA2uiAccordion = createComponentImplementation(AccordionApi as never, AccordionComponent as never);
