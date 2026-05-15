import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { Carousel, CarouselContent, CarouselItem, CarouselPrevious, CarouselNext } from '@/wv-components/ui/carousel';

const CarouselApi = {
  name: 'Carousel',
  schema: z.object({
    children: z.array(z.string()).describe('The slide component IDs.'),
  }).strict(),
};

function CarouselComponent({ props, buildChild }: { props: Record<string, unknown>; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const children = Array.isArray(props.children) ? props.children as string[] : [];

  if (!children.length) return null;

  return (
    <Carousel className="w-full max-w-sm mx-auto">
      <CarouselContent>
        {children.map((childId, i) => (
          <CarouselItem key={i}>
            {buildChild(childId)}
          </CarouselItem>
        ))}
      </CarouselContent>
      <CarouselPrevious />
      <CarouselNext />
    </Carousel>
  );
}

export const WvA2uiCarousel = createComponentImplementation(CarouselApi as never, CarouselComponent as never);
