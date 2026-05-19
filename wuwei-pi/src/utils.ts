/** Shared parsing utilities for Skill three-file format. */

export interface SkillFiles {
  skillJson: string;
  uiJson: string;
  handlersJs: string;
}

export function parseThreeFiles(raw: string): SkillFiles {
  const extract = (startTag: string, endTag: string | null): string => {
    const start = raw.indexOf(startTag);
    if (start === -1) throw new Error(`找不到分隔符: ${startTag}`);
    const begin = start + startTag.length;
    const end = endTag ? raw.indexOf(endTag, begin) : raw.length;
    return raw.slice(begin, end !== -1 ? end : undefined)
      .replace(/^```(?:json|js)?\s*\n?/, '')
      .replace(/\n```\s*$/, '')
      .trim();
  };

  return {
    skillJson:  extract('=== skill.json ===',         '=== genome/ui.json ==='),
    uiJson:     extract('=== genome/ui.json ===',     '=== genome/handlers.js ==='),
    handlersJs: extract('=== genome/handlers.js ===', null),
  };
}

export function validateFilesFormat(files: SkillFiles) {
  try { JSON.parse(files.skillJson); }
  catch (e) { throw new Error(`skill.json 格式错误: ${e}`); }

  try { JSON.parse(files.uiJson); }
  catch (e) { throw new Error(`ui.json 格式错误: ${e}`); }

  if (!files.handlersJs.includes('export function')) {
    throw new Error('handlers.js 缺少 export function 声明');
  }
}

export function extractCoreGoals(raw: string): string[] {
  const match = raw.match(/=== core-goals ===\n([\s\S]*?)(?:\n===|$)/);
  if (match) {
    return match[1].trim().split('\n')
      .map(s => s.replace(/^-\s*/, '').trim())
      .filter(Boolean);
  }
  return ['处理用户请求'];
}

export function extractDesignDecision(raw: string): string {
  const match = raw.match(/=== design-decision ===\n([\s\S]*?)(?:\n===|$)/);
  return match ? match[1].trim() : '';
}
