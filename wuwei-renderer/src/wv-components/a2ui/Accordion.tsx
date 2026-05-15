import { useState } from 'react';
import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Accordion as ShadcnAccordion, AccordionItem, AccordionTrigger, AccordionContent } from '@/wv-components/ui/accordion';

const AccordionApi = {
  name: 'Accordion',
  schema: z.object({
    items: z.array(z.object({
      value: z.string().describe('Unique value for this accordion item.'),
      triggerText: z.string().describe('The trigger text.'),
      contentId: z.string().describe('The ID of the content component.'),
    })).describe('The accordion items.'),
    type: z.enum(['single', 'multiple']).default('single').optional().describe('Whether single or multiple items can be open.'),
  }).strict(),
};

function AccordionComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const items = (Array.isArray(props.items) ? props.items : []) as Array<{ value: string; triggerText: string; contentId: string }>;
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
