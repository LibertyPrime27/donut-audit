package barry.watcher;

import barry.bot.Executor;
import barry.config.Settings;
import barry.render.RenderHook;
import barry.ui.Overlay;
import barry.xray.XrayEngine;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PanicHandler {

    // Panic: when staff are detected, degrade to a seedless human-looking mode
    // within one second. The transition must be:
    // 1. ESP and overlay hidden instantly
    // 2. Mining pattern switches from systematic to random cave-following
    // 3. Rotations switch to full humanizer mode (if not already)
    // 4. Movement speed normalizes
    // 5. No overlay, no tracers, nothing visible on screen
    //
    // Recovery: after the alert clears (hysteresis), gradually re-enable
    // features over 30-60 seconds, not instantly.

    public enum State {
        NORMAL,     // full capability
        ALERT,      // staff detected, monitoring
        PANIC,      // all visible features hidden
        RECOVERING  // gradually re-enabling after alert clears
    }

    private volatile State state = State.NORMAL;
    private final AtomicBoolean processing = new AtomicBoolean(false);

    private final StaffDetector detector;
    private volatile Executor executor;
    private volatile XrayEngine engine;

    private long panicStartTime = 0;
    private long recoveryStartTime = 0;
    private static final long RECOVERY_DURATION = 60_000; // 60 seconds

    // Dwell: how long staff must be present before we disconnect
    private int dwellBeforeDisconnect;
    private volatile boolean disconnectTriggered = false;

    public PanicHandler(StaffDetector detector) {
        this.detector = detector;
        this.dwellBeforeDisconnect = Settings.get().dwellBeforeDisconnect();
    }

    public void setExecutor(Executor executor) { this.executor = executor; }
    public void setEngine(XrayEngine engine) { this.engine = engine; }

    public void tick() {
        if (!processing.compareAndSet(false, true)) return;

        try {
            boolean staffAlert = detector.isAlerted();
            float score = detector.score();

            switch (state) {
                case NORMAL -> {
                    if (staffAlert) {
                        enterAlert();
                    }
                }
                case ALERT -> {
                    if (!staffAlert) {
                        beginRecovery();
                    } else if (detector.alertDuration() > dwellBeforeDisconnect * 1000L) {
                        if (!disconnectTriggered) {
                            System.err.println("[barry] Staff dwell exceeded. Flagging for disconnect.");
                            disconnectTriggered = true;
                        }
                        enterPanic();
                    }
                }
                case PANIC -> {
                    if (!staffAlert) {
                        beginRecovery();
                    }
                }
                case RECOVERING -> {
                    long elapsed = System.currentTimeMillis() - recoveryStartTime;
                    if (staffAlert) {
                        // Staff came back during recovery — re-enter panic
                        enterPanic();
                    } else if (elapsed > RECOVERY_DURATION) {
                        state = State.NORMAL;
                        restoreNormal();
                    }
                }
            }
        } finally {
            processing.set(false);
        }
    }

    private void enterAlert() {
        state = State.ALERT;
        Overlay.panic();
        if (RenderHook.esp() != null) RenderHook.esp().setEnabled(false);
        if (RenderHook.hud() != null) RenderHook.hud().setShowStats(false);
        System.err.println("[barry] ALERT: staff detected. Score: " + detector.score());
    }

    private void enterPanic() {
        long start = System.nanoTime();
        state = State.PANIC;
        panicStartTime = System.currentTimeMillis();

        // 1. Hide all visible features instantly
        Overlay.panic();
        if (RenderHook.esp() != null) {
            RenderHook.esp().setEnabled(false);
        }
        if (RenderHook.hud() != null) {
            RenderHook.hud().setShowStats(false);
        }

        // 2. Switch executor to degraded mode (cave-following, no systematic mining)
        if (executor != null) {
            executor.planner().invalidatePlan();
        }

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        System.err.println("[barry] PANIC entered in " + elapsed + "ms. Score: " + detector.score());

        // The transition should complete in <100ms. Log if it doesn't.
        if (elapsed > 100) {
            System.err.println("[barry] WARNING: panic transition took " + elapsed + "ms (target: <100ms)");
        }
    }

    private void beginRecovery() {
        state = State.RECOVERING;
        recoveryStartTime = System.currentTimeMillis();
        disconnectTriggered = false;
        System.err.println("[barry] Staff alert cleared. Beginning recovery (" + RECOVERY_DURATION / 1000 + "s)");
    }

    private void restoreNormal() {
        // Gradually re-enable features
        Overlay.setVisible(true);
        if (RenderHook.esp() != null) {
            RenderHook.esp().setEnabled(true);
        }
        if (RenderHook.hud() != null) {
            RenderHook.hud().setShowStats(true);
        }
        System.err.println("[barry] Recovery complete. Normal mode restored.");
    }

    public State state() { return state; }
    public boolean isPanicked() { return state == State.PANIC || state == State.ALERT; }
    public boolean isDisconnectTriggered() { return disconnectTriggered; }
    public long panicDuration() {
        return state == State.PANIC ? System.currentTimeMillis() - panicStartTime : 0;
    }

    public void forcePanic() {
        enterPanic();
    }

    public void clear() {
        state = State.NORMAL;
        disconnectTriggered = false;
    }
}
