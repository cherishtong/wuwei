package com.wuwei.sandbox;

import com.wuwei.capability.CapabilitySet;
import com.wuwei.skill.SkillManifest;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * GraalJS sandbox for a single Skill.
 * Isolated Context, shared Engine, zero host access.
 *
 * All JS execution (events, timer callbacks, streaming AI chunks) is
 * serialized through a SingleThreadExecutor, eliminating the need for
 * {@code synchronized(ctx)} and ensuring user events and streaming
 * callbacks never block each other — they simply queue up in FIFO order.
 */
public class SkillRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SkillRuntime.class);
    private static final AtomicInteger activeCount = new AtomicInteger(0);

    private final Context ctx;
    private final String skillId;
    private final CapabilitySet capSet;
    private final Consumer<List<Object>> patchFlush;
    private volatile boolean hasInflightEvent = false;
    private volatile boolean closed = false;

    /** Single-threaded event loop — all JS execution goes through this. */
    private final ExecutorService skillThread;

    private final ScheduledExecutorService scheduler;
    private final Map<Integer, ScheduledFuture<?>> intervals = new ConcurrentHashMap<>();
    private final AtomicInteger timerIdCounter = new AtomicInteger(0);

    public SkillRuntime(Engine engine, SkillManifest manifest,
                        String code, CapabilitySet capSet,
                        Consumer<List<Object>> patchFlush) {
        this.skillId = manifest.id();
        this.capSet = capSet;
        this.patchFlush = patchFlush;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "timer-" + skillId);
            t.setDaemon(true);
            return t;
        });
        this.skillThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "skill-" + skillId);
            t.setDaemon(true);
            return t;
        });

        this.ctx = Context.newBuilder("js")
            .engine(engine)
            .allowHostAccess(HostAccess.NONE)
            .allowExperimentalOptions(true)
            .option("js.ecmascript-version", "2022")
            .option("js.strict", "true")
            .allowIO(IOAccess.NONE)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .resourceLimits(org.graalvm.polyglot.ResourceLimits.newBuilder()
                .statementLimit(10_000_000L, null)
                .build())
            .build();

        injectCapabilities(capSet);
        injectTimerApi();
        loadCode(code);

        // Register streaming enqueue with AiCapability so ai.askStream
        // callbacks execute on this skill's event loop.
        capSet.registerStreamEnqueue(task -> skillThread.execute(() -> {
            capSet.drainPatches(); // clear stale
            task.run();             // execute JS callback (onChunk / onDone)
            List<Object> patches = capSet.drainPatches();
            if (!patches.isEmpty() && patchFlush != null) {
                patchFlush.accept(patches);
            }
        }));

        activeCount.incrementAndGet();
        log.info("SkillRuntime created: {} (active: {})", skillId, activeCount.get());
    }

    // ── Capabilities ──────────────────────────────────────────────

    private void injectCapabilities(CapabilitySet capSet) {
        Value bindings = ctx.getBindings("js");
        bindings.putMember("capability", capSet.toProxyObject());
    }

    // ── Timer API ─────────────────────────────────────────────────

    private void injectTimerApi() {
        Value bindings = ctx.getBindings("js");

        bindings.putMember("setInterval", (ProxyExecutable) args -> {
            Value callback = args[0];
            int ms = args[1].asInt();
            if (!callback.canExecute()) return -1;

            int id = timerIdCounter.incrementAndGet();
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                skillThread.execute(() -> {
                    if (closed) return;
                    try {
                        capSet.drainPatches();
                        callback.execute();
                        List<Object> patches = capSet.drainPatches();
                        if (!patches.isEmpty() && patchFlush != null) {
                            patchFlush.accept(patches);
                        }
                    } catch (Exception e) {
                        log.warn("setInterval callback error in {}: {}", skillId, e.getMessage());
                    }
                });
            }, ms, ms, TimeUnit.MILLISECONDS);
            intervals.put(id, future);
            return id;
        });

        ProxyExecutable clearTimer = args -> {
            int id = args[0].asInt();
            ScheduledFuture<?> future = intervals.remove(id);
            if (future != null) future.cancel(false);
            return null;
        };
        bindings.putMember("clearInterval", clearTimer);
        bindings.putMember("clearTimeout", clearTimer);

        bindings.putMember("setTimeout", (ProxyExecutable) args -> {
            Value callback = args[0];
            int ms = args[1].asInt();
            if (!callback.canExecute()) return -1;

            int id = timerIdCounter.incrementAndGet();
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                skillThread.execute(() -> {
                    if (closed) return;
                    try {
                        capSet.drainPatches();
                        callback.execute();
                        List<Object> patches = capSet.drainPatches();
                        if (!patches.isEmpty() && patchFlush != null) {
                            patchFlush.accept(patches);
                        }
                    } catch (Exception e) {
                        log.warn("setTimeout callback error in {}: {}", skillId, e.getMessage());
                    } finally {
                        intervals.remove(id);
                    }
                });
            }, ms, TimeUnit.MILLISECONDS);
            intervals.put(id, future);
            return id;
        });
    }

    // ── Code loading ──────────────────────────────────────────────

    private void loadCode(String code) {
        try {
            ctx.eval(Source.newBuilder("js", code, skillId + "/handlers.js").buildLiteral());
        } catch (Exception e) {
            log.error("Failed to load code for {}: {}", skillId, e.getMessage());
            throw new RuntimeException("Code load failed: " + e.getMessage(), e);
        }
    }

    // ── Event emission ────────────────────────────────────────────

    /**
     * Emit an event to the skill with a wall-clock timeout.
     * Submits to the single-threaded event loop and waits on a future.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> emitWithTimeout(String eventId,
                                                Map<String, Object> inputs,
                                                int timeoutSeconds)
            throws TimeoutException, ExecutionException {
        hasInflightEvent = true;
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        try {
            skillThread.execute(() -> {
                try {
                    future.complete(emit(eventId, inputs));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            ctx.interrupt(java.time.Duration.ZERO);
            future.cancel(true);
            log.warn("Skill {} timed out on event {}", skillId, eventId);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.interrupt(java.time.Duration.ZERO);
            future.cancel(true);
            throw new RuntimeException("Interrupted", e);
        } finally {
            hasInflightEvent = false;
        }
    }

    private Map<String, Object> emit(String eventId, Map<String, Object> inputs) {
        capSet.drainPatches(); // clear stale patches from timer callbacks

        // Inject __inputs__ as a JS object
        Value jsInputs = ctx.eval("js", "({})");
        if (inputs != null) {
            inputs.forEach((k, v) -> {
                if (v instanceof Map<?, ?> m) {
                    Value elem = ctx.eval("js", "({})");
                    m.forEach((pk, pv) -> elem.putMember(pk.toString(), pv));
                    jsInputs.putMember(k, elem);
                } else {
                    jsInputs.putMember(k, v);
                }
            });
        }
        ctx.getBindings("js").putMember("__inputs__", jsInputs);

        String handlerName = toHandlerName(eventId);
        System.out.println("[SkillRuntime] emit: eventId=" + eventId + " handlerName=" + handlerName + " inputs=" + inputs);
        Value bindings = ctx.getBindings("js");
        Value handler = bindings.getMember(handlerName);

        if (handler != null && handler.canExecute()) {
            try {
                System.out.println("[SkillRuntime] calling " + handlerName);
                Value cap = bindings.getMember("capability");
                handler.execute(jsInputs, cap);
                System.out.println("[SkillRuntime] " + handlerName + " returned");
            } catch (Exception e) {
                System.out.println("[SkillRuntime] Handler " + handlerName + " threw: " + e.getMessage());
                log.error("Handler {}::{} threw: {}", skillId, handlerName, e.getMessage());
                throw new RuntimeException("Handler error: " + e.getMessage(), e);
            }
        } else {
            System.out.println("[SkillRuntime] No handler " + handlerName + " for event " + eventId);
        }

        List<Object> patches = capSet.drainPatches();
        System.out.println("[SkillRuntime] patches after drain: " + patches);
        return Map.of("patches", patches);
    }

    public boolean hasInflightEvents() {
        return hasInflightEvent;
    }

    public static int getActiveCount() {
        return activeCount.get();
    }

    // ── Handler name convention ───────────────────────────────────

    private String toHandlerName(String eventId) {
        if ("__init__".equals(eventId)) return "onInit";
        if ("__destroy__".equals(eventId)) return "onDestroy";
        String[] parts = eventId.split("-");
        StringBuilder sb = new StringBuilder("on");
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    // ── Cleanup ───────────────────────────────────────────────────

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        // Unregister streaming enqueue
        capSet.unregisterStreamEnqueue();

        // Cancel all timers
        for (ScheduledFuture<?> f : intervals.values()) {
            f.cancel(false);
        }
        intervals.clear();
        scheduler.shutdownNow();

        // Shut down the event loop
        skillThread.shutdown();
        try {
            if (!skillThread.awaitTermination(3, TimeUnit.SECONDS)) {
                skillThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            skillThread.shutdownNow();
        }

        try {
            ctx.close(true);
        } catch (Exception e) {
            log.warn("Error closing context for {}: {}", skillId, e.getMessage());
        }
        activeCount.decrementAndGet();
        log.info("SkillRuntime closed: {} (active: {})", skillId, activeCount.get());
    }
}
