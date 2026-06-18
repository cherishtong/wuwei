import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
import { kernel } from './kernel';
import { initBridge } from './bridge';
import { initAiModalBridge } from './components/AiChatModal';
import './index.css';

if ('Notification' in window && Notification.permission === 'default') {
  Notification.requestPermission();
}

kernel.init();
initBridge();
initAiModalBridge();

// Forward browser console to kernel render-log
['log','warn','error'].forEach(function(lvl) {
  var orig = (console as any)[lvl];
  (console as any)[lvl] = function() {
    orig.apply(console, arguments);
    try { kernel.sendRenderLog(lvl, Array.from(arguments).join(' ')); } catch(e) {}
  };
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

console.log('[wuwei-renderer] initialized v0.0.1-beta (React)');
