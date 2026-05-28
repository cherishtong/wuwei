import { useEffect, useState } from 'react';
import logo from '../assets/logo.png';

interface WwLoadingProps {
  onFadeDone?: () => void;
}

export function WwLoading({ onFadeDone }: WwLoadingProps) {
  const [fadeOut, setFadeOut] = useState(false);

  useEffect(() => {
    const handler = () => {
      setFadeOut(true);
      if (onFadeDone) {
        setTimeout(onFadeDone, 400);
      }
    };
    window.addEventListener('kernel-ready', handler);
    return () => window.removeEventListener('kernel-ready', handler);
  }, [onFadeDone]);

  return (
    <div
      className="fixed inset-0 z-50 flex flex-col items-center justify-center transition-opacity duration-400"
      style={{
        background: '#f9f6f1',
        backgroundImage: 'radial-gradient(#e5e1da 1px, transparent 1px)',
        backgroundSize: '40px 40px',
        opacity: fadeOut ? 0 : 1,
        transition: 'opacity 0.4s ease-out',
      }}
    >
      {/* Logo block */}
      <img
        src={logo}
        alt="Wuwei"
        className="w-20 h-20 rounded-2xl object-cover mb-6"
        style={{
          animation: 'wuwei-breathe 3s ease-in-out infinite',
          boxShadow: '0 4px 24px rgba(185,28,28,0.15)',
        }}
      />

      {/* Brand */}
      <h1
        className="text-xl font-bold mb-10 tracking-wider"
        style={{
          fontFamily: '"Noto Serif SC", "Songti SC", serif',
          color: '#1a1a1b',
          letterSpacing: '0.2em',
        }}
      >
        无为
      </h1>

      {/* Quote — classical philosophy */}
      <blockquote
        className="text-center mb-10 max-w-md leading-loose"
        style={{
          fontFamily: '"Noto Serif SC", "Songti SC", serif',
          fontSize: '20px',
          fontWeight: 900,
          color: '#1a1a1b',
          letterSpacing: '0.08em',
          opacity: 0.82,
        }}
      >
        大巧若拙，大音希声。
        <br />
        无为运行时，静听数字生命自生长。
      </blockquote>


      {/* Shimmer bar */}
      <div className="w-32 h-0.5 rounded-full overflow-hidden" style={{ background: 'rgba(0,0,0,0.06)' }}>
        <div
          className="h-full w-full rounded-full"
          style={{
            background: 'linear-gradient(90deg, transparent, #b91c1c, transparent)',
            backgroundSize: '200% 100%',
            animation: 'wuwei-shimmer 2s linear infinite',
          }}
        />
      </div>
    </div>
  );
}
