package com.wuwei.skill;

/**
 * Placeholder runtime for browser-js skills.
 * No GraalJS context — handlers execute in the browser.
 * Exists only to satisfy the LoadedSkill.runtime() type contract.
 */
public record BrowserSkillRuntime(SkillManifest manifest, String handlersJs) implements AutoCloseable {
    @Override
    public void close() {}
}