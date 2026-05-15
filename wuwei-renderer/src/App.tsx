import { ThemeProvider } from './contexts/ThemeContext';
import { TooltipProvider } from '@/wv-components/ui/tooltip';
import { WwShell } from './components/WwShell';

export function App() {
  return (
    <TooltipProvider>
      <ThemeProvider>
        <WwShell />
      </ThemeProvider>
    </TooltipProvider>
  );
}
