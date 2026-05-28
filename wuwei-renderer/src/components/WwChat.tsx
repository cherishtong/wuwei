import { useState, useEffect, useRef, useCallback } from 'react';
import { useTheme } from '../contexts/ThemeContext';
import type { ChatMessage } from '../stores/ConversationStore';
import logo from '../assets/logo.png';

interface WwChatProps {
  messages: ChatMessage[];
  generating: boolean;
  stepDesc: string;
  placeholder?: string;
  contextLabel?: string;
  onSend: (text: string) => void;
  compact?: boolean;
  hideSuggestions?: boolean;
  children?: React.ReactNode;
}

export function WwChat({
  messages,
  generating,
  stepDesc,
  placeholder = '描述你想要的应用...',
  contextLabel,
  onSend,
  compact = false,
  hideSuggestions = false,
  children,
}: WwChatProps) {
  const { resolved: theme } = useTheme();
  const isDark = theme === 'dark';
  const [value, setValue] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  // Theme tokens
  const frameBg = compact ? 'transparent' : isDark ? '#18181b' : '#fdfcf9';
  const frameBorder = compact ? 'transparent' : isDark ? 'rgba(255,255,255,0.07)' : '#e5e1da';
  const frameShadow = compact ? 'none' : isDark
    ? '0 2px 40px rgba(0,0,0,0.3), 0 1px 3px rgba(0,0,0,0.2)'
    : '0 2px 40px rgba(0,0,0,0.04), 0 1px 3px rgba(0,0,0,0.03)';
  const systemBubbleBg = isDark ? '#27272a' : '#f5f2eb';
  const dividerColor = isDark ? 'rgba(255,255,255,0.06)' : '#e5e1da';
  const inputBg = isDark ? '#27272a' : '#f5f2eb';
  const inputBorder = isDark ? 'rgba(255,255,255,0.08)' : '#e5e1da';
  const inputFocusBorder = isDark ? 'rgba(255,255,255,0.15)' : '#d1cbc0';
  const sendDisabledBg = isDark ? 'rgba(255,255,255,0.06)' : '#e8e4db';

  // Auto-scroll
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
    }
  }, [messages]);

  // Auto-resize textarea
  useEffect(() => {
    const el = textareaRef.current;
    if (el) {
      el.style.height = 'auto';
      el.style.height = Math.min(el.scrollHeight, compact ? 120 : 200) + 'px';
    }
  }, [value, compact]);

  const onSubmit = useCallback(() => {
    const text = value.trim();
    if (!text || generating) return;
    onSend(text);
    setValue('');
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
    }
  }, [value, generating, onSend]);

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      onSubmit();
    }
  };

  const isNewConversation = messages.length === 0;

  return (
    <div className={`flex flex-col h-full ${compact ? '' : 'rounded-3xl overflow-hidden'}`}
      style={{
        background: frameBg,
        border: frameBorder === 'transparent' ? 'none' : `1px solid ${frameBorder}`,
        boxShadow: frameShadow,
      }}
    >
      {/* Messages area */}
      <div className="flex-1 min-h-0 relative">
        <div ref={scrollRef} className="absolute inset-0 overflow-auto">
          <div className={`${compact ? 'px-3 py-3' : 'px-5 py-6'} space-y-6`}>
            {isNewConversation && !hideSuggestions && (
              <div className="flex flex-col items-center gap-3 pt-8 pb-4">
                <img src={logo} alt="Wuwei" className="w-12 h-12 rounded-xl object-cover opacity-80" />
                <p className="text-sm text-muted-foreground text-center">
                  {contextLabel ? `优化「${contextLabel}」` : '描述你想要的应用，无为将为你生成'}
                </p>
              </div>
            )}

            {messages.map((msg) => (
              <div key={msg.id} className={`flex gap-3 ${msg.role === 'user' ? 'justify-end' : ''}`}>
                {msg.role === 'system' && (
                  <img
                    src={logo}
                    alt="Wuwei"
                    className="w-8 h-8 rounded-lg flex-shrink-0 object-cover mt-0.5"
                  />
                )}

                <div className={`max-w-[80%] ${msg.role === 'user' ? '' : ''}`}>
                  <div
                    className={`px-4 py-3 rounded-2xl text-sm leading-relaxed whitespace-pre-line ${
                      msg.role === 'user'
                        ? 'bg-foreground text-background rounded-br-md'
                        : 'text-foreground rounded-bl-md'
                    }`}
                    style={msg.role === 'system' ? { background: systemBubbleBg } : undefined}
                  >
                    {msg.role === 'system' && msg.content.startsWith('正在') ? (
                      <span className="flex items-center gap-2">
                        <span className="w-1.5 h-1.5 rounded-full bg-yellow-500 animate-pulse flex-shrink-0" />
                        {msg.content}
                      </span>
                    ) : (
                      <span
                        dangerouslySetInnerHTML={{
                          __html: msg.content.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>'),
                        }}
                      />
                    )}
                  </div>
                  <span className="text-[10px] text-muted-foreground mt-1 px-1 block">
                    {msg.time}
                  </span>
                </div>

                {msg.role === 'user' && (
                  <div className="w-8 h-8 rounded-lg bg-accent flex items-center justify-center flex-shrink-0 mt-0.5">
                    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                      <circle cx="8" cy="5" r="3" />
                      <path d="M2 14c0-3.3 2.7-6 6-6s6 2.7 6 6" />
                    </svg>
                  </div>
                )}
              </div>
            ))}

            {children}
            <div className="h-2" />
          </div>
        </div>

        {/* Fade gradients */}
        {!compact && (
          <>
            <div className="absolute top-0 left-0 right-0 h-6 pointer-events-none z-10"
              style={{ background: `linear-gradient(to bottom, ${frameBg}, transparent)` }} />
            <div className="absolute bottom-0 left-0 right-0 h-6 pointer-events-none z-10"
              style={{ background: `linear-gradient(to top, ${frameBg}, transparent)` }} />
          </>
        )}
      </div>

      {/* Divider */}
      <div className={`flex-shrink-0 ${compact ? 'px-3' : 'px-5'}`}>
        <div className="h-px" style={{ background: `linear-gradient(to right, transparent, ${dividerColor} 20%, ${dividerColor} 80%, transparent)` }} />
      </div>

      {/* Input */}
      <div className={`flex-shrink-0 ${compact ? 'px-3 py-2' : 'px-5 py-4'}`}>
        {contextLabel && (
          <div className="mb-2 flex items-center">
            <span className="px-2 py-0.5 rounded-md bg-accent/10 text-xs text-accent font-medium">
              {contextLabel}
            </span>
          </div>
        )}
        <div
          className={`flex items-end gap-3 rounded-2xl border px-4 py-3 transition-all ${compact ? 'gap-2 px-3 py-2' : ''}`}
          style={{ background: inputBg, borderColor: inputBorder }}
          onFocusCapture={() => {
            const el = textareaRef.current?.parentElement;
            if (el) el.style.borderColor = inputFocusBorder;
          }}
          onBlurCapture={() => {
            const el = textareaRef.current?.parentElement;
            if (el) el.style.borderColor = inputBorder;
          }}
        >
          <textarea
            ref={textareaRef}
            className="flex-1 resize-none bg-transparent text-sm placeholder:text-muted-foreground focus:outline-none py-1"
            rows={1}
            placeholder={placeholder}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={onKeyDown}
            disabled={generating}
          />

          <button
            className={`flex-shrink-0 p-2 rounded-lg transition-all ${
              value.trim() && !generating
                ? 'bg-foreground text-background hover:opacity-80'
                : 'text-muted-foreground cursor-not-allowed'
            }`}
            style={(!value.trim() || generating) ? { background: sendDisabledBg } : undefined}
            onClick={onSubmit}
            disabled={!value.trim() || generating}
            title="发送 (Enter)"
          >
            {generating ? (
              <svg className="animate-spin" width="18" height="18" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
                <circle cx="8" cy="8" r="6" strokeOpacity="0.3" />
                <path d="M14 8a6 6 0 00-10.24-4.24" strokeLinecap="round" />
              </svg>
            ) : (
              <svg width="18" height="18" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                <line x1="8" y1="2" x2="8" y2="14" />
                <polyline points="4 8 8 14 12 8" />
              </svg>
            )}
          </button>
        </div>
        <p className={`text-center mt-2 text-[10px] text-muted-foreground ${compact ? 'mt-1.5' : ''}`}>
          Enter 发送 &middot; Shift+Enter 换行
        </p>
      </div>
    </div>
  );
}
