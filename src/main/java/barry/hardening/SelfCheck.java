package barry.hardening;

import java.lang.instrument.Instrumentation;
import java.util.*;

public final class SelfCheck {

    private SelfCheck() {}

    public record CheckResult(String name, boolean passed, String detail) {}

    // Run all integrity checks. Returns false if any critical check fails.
    public static List<CheckResult> runAll(Instrumentation inst) {
        List<CheckResult> results = new ArrayList<>();

        results.add(checkClassLoaderIsolation());
        results.add(checkAgentVisibility());
        results.add(checkThreadNames());
        results.add(checkHeapExposure(inst));
        results.add(checkRemapState());

        return results;
    }

    public static boolean allPassed(List<CheckResult> results) {
        return results.stream().allMatch(CheckResult::passed);
    }

    private static CheckResult checkClassLoaderIsolation() {
        boolean ok = ClassHygiene.selfCheck();
        return new CheckResult("classloader_isolation", ok,
            ok ? "barry classes not on system classpath"
               : "barry classes findable via system classloader");
    }

    private static CheckResult checkAgentVisibility() {
        boolean ok = ClassHygiene.agentCheck();
        return new CheckResult("agent_visibility", ok,
            ok ? "agent not visible in JVM args"
               : "agent JAR name visible in RuntimeMXBean.getInputArguments");
    }

    private static CheckResult checkThreadNames() {
        boolean ok = true;
        StringBuilder detail = new StringBuilder();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("barry-")) {
                ok = false;
                detail.append(t.getName()).append(" ");
            }
        }
        return new CheckResult("thread_names", ok,
            ok ? "no barry-prefixed threads found"
               : "exposed threads: " + detail.toString().trim());
    }

    private static CheckResult checkHeapExposure(Instrumentation inst) {
        if (inst == null) {
            return new CheckResult("heap_exposure", true, "no instrumentation available to check");
        }

        // Check if our classes are enumerable via Instrumentation.getAllLoadedClasses()
        int barryClassCount = 0;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().startsWith("barry.")) {
                barryClassCount++;
            }
        }

        // Our classes will always be loaded — the question is whether
        // something else is scanning for them. We just report the count.
        return new CheckResult("heap_exposure", true,
            barryClassCount + " barry classes loaded (expected; not removable)");
    }

    private static CheckResult checkRemapState() {
        try {
            var state = barry.attach.Remap.state();
            boolean ok = state != barry.attach.Remap.State.DEGRADED;
            return new CheckResult("remap_state", ok,
                "Remap state: " + state.name());
        } catch (Throwable e) {
            return new CheckResult("remap_state", false,
                "Remap check failed: " + e.getMessage());
        }
    }
}
