// bridge.ts — WebSocket messages → store dispatch + window CustomEvent (backward compat)
import { kernel } from './kernel';
import { conversationStore } from './stores/ConversationStore';
import { surfaceStore } from './stores/SurfaceStore';
import { systemStore } from './stores/SystemStore';
import { consoleStore } from './stores/ConsoleStore';

function dispatch(name: string, detail: unknown) {
  window.dispatchEvent(new CustomEvent(name, { detail }));
}

export function initBridge() {
  if ('Notification' in window && Notification.permission === 'default') {
    Notification.requestPermission();
  }

  // ns:conv — conversation updates
  kernel.onNamespace('conv', (msg: Record<string, any>) => {
    conversationStore.dispatch(msg);
    switch (msg.type) {
      case 'conversation-update':
        dispatch('conversation-update', msg);
        break;
      case 'conversation-created':
        dispatch('conversation-created', msg);
        break;
      case 'conversation-list':
        dispatch('conversation-list', msg);
        break;
      case 'conversation-detail':
        dispatch('conversation-detail', msg);
        break;
      case 'conversation-deleted':
        dispatch('conversation-deleted', msg);
        break;
      case 'conversation-ready':
        dispatch('conversation-ready', msg);
        break;
      case 'thread-active-skill-set':
        dispatch('thread-active-skill-set', msg);
        break;
      case 'thread-detail':
        dispatch('thread-detail', msg);
        break;
      default:
        dispatch(`conv:${msg.type}`, msg);
    }
  });

  // ns:ui — A2UI rendering
  kernel.onNamespace('ui', (msg: Record<string, any>) => {
    surfaceStore.dispatch(msg);
    dispatch(msg.type, msg);
    // Keep conversationStore.activeSkillId in sync
    if (msg.type === 'skill-activated' && msg.threadId) {
      conversationStore.updateActiveSkillSilent(msg.threadId as string, msg.skillId as string);
    } else if (msg.type === 'skill-deactivated' && msg.threadId) {
      conversationStore.updateActiveSkillSilent(msg.threadId as string, null);
    }
    // event-ack may carry patches from the kernel — dispatch them too
    if (msg.type === 'event-ack' && msg.patches && Array.isArray(msg.patches) && msg.patches.length > 0) {
      dispatch('a2ui-patch', { skillId: msg.skillId, threadId: msg.threadId, patches: msg.patches });
    }
  });

  // ns:sys — system state
  kernel.onNamespace('sys', (msg: Record<string, any>) => {
    systemStore.dispatch(msg);
    dispatch(msg.type, msg);
    // OS notification for system-notify
    if (msg.type === 'system-notify') {
      try {
        if ('Notification' in window && Notification.permission === 'granted') {
          new Notification(msg.title || 'Wuwei', { body: msg.body || '' });
        }
      } catch { /* not available */ }
    }
  });

  // ns:log — debug logs
  kernel.onNamespace('log', (msg: Record<string, any>) => {
    consoleStore.dispatch(msg);
    dispatch(msg.type, msg);
  });

  // kernel-ready triggers skill list request
  kernel.onNamespace('sys', (msg: Record<string, any>) => {
    if (msg.type === 'kernel-ready') {
      kernel.listSkills();
    }
  });

  // Fallback: dispatch un-namespaced messages (e.g. skill-source)
  kernel.onMessage((msg: Record<string, any>) => {
    if (!msg.ns && msg.type) {
      dispatch(msg.type, msg);
    }
  });
}
