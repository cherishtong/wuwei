// bridge.ts — WebSocket messages → window CustomEvent dispatch
import { kernel } from './kernel';

function dispatch(name: string, detail: unknown) {
  window.dispatchEvent(new CustomEvent(name, { detail }));
}

export function initBridge() {
  // Request OS-level notification permission early
  if ('Notification' in window && Notification.permission === 'default') {
    Notification.requestPermission();
  }

  kernel.onMessage((msg: Record<string, any>) => {
    switch (msg.type) {
      case 'kernel-ready':
        dispatch('kernel-ready', msg);
        // Request skill list on connect
        kernel.listSkills();
        break;
      case 'skill-list':
        dispatch('skill-list', msg);
        break;
      case 'skill-loading':
        dispatch('skill-loading', msg);
        break;
      case 'skill-activated':
        dispatch('skill-activated', msg);
        break;
      case 'skill-deactivated':
        dispatch('skill-deactivated', msg);
        break;
      case 'a2ui-patch':
        dispatch('a2ui-patch', msg);
        break;
      case 'event-ack':
        dispatch('event-ack', msg);
        break;
      case 'gate-request':
        dispatch('gate-request', msg);
        break;
      case 'plan-step':
        dispatch('plan-step', msg);
        break;
      case 'repair-attempt':
        dispatch('repair-attempt', msg);
        break;
      case 'skill-log':
        dispatch('skill-log', msg);
        break;
      case 'guardian-warning':
        dispatch('guardian-warning', msg);
        break;
      case 'kernel-error':
        dispatch('kernel-error', msg);
        break;
      case 'system-notify':
        dispatch('system-notify', msg);
        // Also show as actual OS notification when triggered by os.notify
        try {
          if ('Notification' in window && Notification.permission === 'granted') {
            new Notification(msg.title || 'Wuwei', { body: msg.body || '' });
          }
        } catch { /* not available */ }
        break;
      case 'skill-source':
        dispatch('skill-source', msg);
        break;
      default:
        console.debug('[bridge] unhandled message type:', msg.type);
    }
  });
}
