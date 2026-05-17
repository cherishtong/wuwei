import { Component, type ReactNode } from 'react';
import { ThemeProvider } from './contexts/ThemeContext';
import { TooltipProvider } from '@/wv-components/ui/tooltip';
import { WwShell } from './components/WwShell';

class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  constructor(props: { children: ReactNode }) {
    super(props);
    this.state = { error: null };
  }
  static getDerivedStateFromError(error: Error) {
    return { error };
  }
  componentDidCatch(error: Error, info: { componentStack: string }) {
    console.error('[ErrorBoundary]', error.message, error.stack, info.componentStack);
  }
  render() {
    if (this.state.error) {
      return (
        <div style={{ padding: 40, color: '#ef4444', background: '#1a1a1a', height: '100vh' }}>
          <h1 style={{ fontSize: 20, marginBottom: 12 }}>Application Error</h1>
          <pre style={{ fontSize: 13, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
            {this.state.error.message}
          </pre>
        </div>
      );
    }
    return this.props.children;
  }
}

export function App() {
  return (
    <ErrorBoundary>
      <TooltipProvider>
        <ThemeProvider>
          <WwShell />
        </ThemeProvider>
      </TooltipProvider>
    </ErrorBoundary>
  );
}
