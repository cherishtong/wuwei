import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
import { kernel } from './kernel';
import { initBridge } from './bridge';
import './index.css';

if ('Notification' in window && Notification.permission === 'default') {
  Notification.requestPermission();
}

kernel.init();
initBridge();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

console.log('[wuwei-renderer] initialized v6.5.0 (React)');
