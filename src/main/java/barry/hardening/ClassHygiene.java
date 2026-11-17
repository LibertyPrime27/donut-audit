package barry.hardening;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public final class ClassHygiene {

    private ClassHygiene() {}

    // Strip identifiable strings from loaded classes in memory.
    // After attach, our class names and method names are on the heap.
    // This can't erase them from the class metadata (JVM internals),
    // but it can clear our string constants pool, making casual
    // heap inspection harder.

    private static final List<String> POISONED_STRINGS = new ArrayList<>();

    public static void scrub() {
        // Replace string constants in our classes with garbage
        // This modifies the char[] backing of interned strings
        for (String s : POISONED_STRINGS) {
            poison(s);
        }
    }

    public static void register(String s) {
        if (s != null && !s.isEmpty()) {
            POISONED_STRINGS.add(s);
        }
    }

    private static void poison(String s) {
        try {
            // In Java 9+, String uses byte[] value internally
            Field valueField = String.class.getDeclaredField("value");
            valueField.setAccessible(true);
            byte[] value = (byte[]) valueField.get(s);
            if (value != null) {
                Arrays.fill(value, (byte) 0);
            }
        } catch (Throwable ignored) {
            // Fail silent — older JVMs use char[], or the field is locked
            try {
                Field valueField = String.class.getDeclaredField("value");
                valueField.setAccessible(true);
                Object val = valueField.get(s);
                if (val instanceof char[] chars) {
                    Arrays.fill(chars, '\0');
                }
            } catch (Throwable ignored2) {}
        }
    }

    // Thread name sanitization: rename our daemon threads to look vanilla
    public static void sanitizeThreadNames() {
        Map<String, String> renames = Map.of(
            "barry-tick", "JIT Compilation Thread",
            "barry-render-sync", "C1 CompilerThread",
            "barry-executor", "Finalizer",
            "barry-packet-poll", "Reference Handler"
        );

        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            String replacement = renames.get(name);
            if (replacement != null) {
                t.setName(replacement + "-" + (t.threadId() & 0xFF));
            }
        }
    }

    // Verify no barry-named classes are findable via standard classloader
    public static boolean selfCheck() {
        try {
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            sys.loadClass("barry.Bootstrap");
            // If this succeeds, we're on the system classpath — bad
            return false;
        } catch (ClassNotFoundException e) {
            // Good — not findable via system classloader
            return true;
        }
    }

    // Check that our agent isn't listed in the management bean
    public static boolean agentCheck() {
        try {
            // java.lang.management.RuntimeMXBean.getInputArguments()
            // includes -javaagent: entries
            Class<?> mfClass = Class.forName("java.lang.management.ManagementFactory");
            Method getRuntimeMXBean = mfClass.getMethod("getRuntimeMXBean");
            Object mxBean = getRuntimeMXBean.invoke(null);
            Method getInputArgs = mxBean.getClass().getMethod("getInputArguments");
            @SuppressWarnings("unchecked")
            List<String> args = (List<String>) getInputArgs.invoke(mxBean);

            for (String arg : args) {
                if (arg.contains("barry") || arg.contains("donut-audit")) {
                    return false; // our agent is visible in JVM args
                }
            }
            return true;
        } catch (Throwable e) {
            return true; // can't check = assume ok
        }
    }
}
