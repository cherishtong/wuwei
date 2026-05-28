// SystemStore.ts — manages system-level state (ns:sys)
// Replaces scattered window.addEventListener in WwShell/WwSidebar/WwNavbar

export interface SkillMeta {
  id: string;
  name: string;
  status: string;
  version: string;
}

export interface GateRequest {
  skillId: string;
  threadId: string;
  capName: string;
  reason: string;
}

export interface SystemNotification {
  title: string;
  body: string;
}

type SystemListener = () => void;

const listeners: SystemListener[] = [];
let kernelReady_ = false;
let kernelPort_ = 0;
let kernelVersion_ = '';
let skillList_: SkillMeta[] = [];
let gateRequests_: GateRequest[] = [];
let notifications_: SystemNotification[] = [];
let systemErrors_: { skillId: string; code: string; message: string }[] = [];
let skillLoading_: string | null = null;
let modelRouting_: Record<string, Record<string, string>> = {};

function notify() {
  listeners.forEach((fn) => fn());
}

export const systemStore = {
  dispatch(msg: Record<string, unknown>) {
    const type = msg.type as string;
    switch (type) {
      case 'kernel-ready':
        kernelReady_ = true;
        kernelPort_ = msg.port as number;
        kernelVersion_ = msg.version as string;
        notify();
        break;
      case 'skill-list':
        skillList_ = (msg.skills as SkillMeta[]) || [];
        notify();
        break;
      case 'skill-loading':
        skillLoading_ = msg.skillId as string;
        notify();
        break;
      case 'gate-request':
        gateRequests_.push({
          skillId: msg.skillId as string,
          threadId: msg.threadId as string,
          capName: msg.capName as string,
          reason: msg.reason as string,
        });
        notify();
        break;
      case 'guardian-warning':
      case 'kernel-error':
        systemErrors_.push({
          skillId: msg.skillId as string,
          code: msg.code as string,
          message: msg.message as string,
        });
        notify();
        break;
      case 'system-notify':
        notifications_.push({
          title: msg.title as string,
          body: msg.body as string,
        });
        notify();
        break;
      case 'model-routing-list':
        modelRouting_ = (msg.entries as Record<string, Record<string, string>>) || {};
        notify();
        break;
    }
  },

  onChange(fn: SystemListener) {
    listeners.push(fn);
    return () => {
      const idx = listeners.indexOf(fn);
      if (idx >= 0) listeners.splice(idx, 1);
    };
  },

  isKernelReady(): boolean { return kernelReady_; },
  getKernelPort(): number { return kernelPort_; },
  getKernelVersion(): string { return kernelVersion_; },
  getSkills(): SkillMeta[] { return skillList_; },
  getGateRequests(): GateRequest[] { return gateRequests_; },
  getNotifications(): SystemNotification[] { return notifications_; },
  getSkillLoading(): string | null { return skillLoading_; },
  getModelRouting(): Record<string, Record<string, string>> { return modelRouting_; },

  clearGateRequest(skillId: string, capName: string) {
    gateRequests_ = gateRequests_.filter(g => !(g.skillId === skillId && g.capName === capName));
    notify();
  },

  clearError(index: number) {
    systemErrors_.splice(index, 1);
    notify();
  },
};
