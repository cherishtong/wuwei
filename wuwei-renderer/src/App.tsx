import { ThemeProvider } from './contexts/ThemeContext';
import { WwShell } from './components/WwShell';

export function App() {
  return (
    <ThemeProvider>
      <WwShell />
    </ThemeProvider>
  );
}
