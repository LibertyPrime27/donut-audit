package barry;

import barry.attach.Remap;
import barry.ui.Overlay;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class Bootstrap {

    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static final AtomicReference<Instrumentation> INST = new AtomicReference<>();
    private static volatile boolean ejecting = false;

    private Bootstrap() {}

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        if ("eject".equals(args)) {
            eject();
            return;
        }

        if (!LOADED.compareAndSet(false, true)) {
            System.err.println("[barry] Already loaded, ignoring duplicate attach.");
            return;
        }

        INST.set(inst);

        try {
            String version = detectVersion();
            if (version == null) {
                System.err.println("[barry] Could not detect Minecraft version. Entering degraded mode.");
                Remap.initDegraded();
            } else {
                System.err.println("[barry] Detected version: " + version);
                Remap.init(version, inst);
            }

            Overlay.init(inst);
            System.err.println("[barry] Attached successfully.");
        } catch (Throwable t) {
            System.err.println("[barry] Fatal during attach:");
            t.printStackTrace(System.err);
            LOADED.set(false);
        }
    }

    public static void eject() {
        if (ejecting) return;
        ejecting = true;
        System.err.println("[barry] Ejecting...");
        try {
            Overlay.shutdown();
            Remap.shutdown();
            LOADED.set(false);
            INST.set(null);
            System.err.println("[barry] Ejected cleanly.");
        } catch (Throwable t) {
            System.err.println("[barry] Error during eject:");
            t.printStackTrace(System.err);
        } finally {
            ejecting = false;
        }
    }

    public static Instrumentation instrumentation() {
        return INST.get();
    }

    public static boolean isLoaded() {
        return LOADED.get();
    }

    private static String detectVersion() {
        try {
            ClassLoader cl = findMinecraftClassLoader();
            if (cl == null) return null;

            // Try SharedConstants.getCurrentVersion().getName() (Mojmap)
            String[] sharedConstantsNames = {
                "net.minecraft.SharedConstants",
                "net.minecraft.DetectedVersion",
            };

            for (String name : sharedConstantsNames) {
                try {
                    Class<?> cls = cl.loadClass(name);
                    // Try getCurrentVersion().getName()
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getParameterCount() == 0 && m.getReturnType() != void.class
                                && m.getReturnType() != cls && !m.getReturnType().isPrimitive()) {
                            m.setAccessible(true);
                            Object version = m.invoke(null);
                            if (version != null) {
                                Method nameMethod = findMethod(version.getClass(), "getName", "getId", "getVersion");
                                if (nameMethod != null) {
                                    nameMethod.setAccessible(true);
                                    Object result = nameMethod.invoke(version);
                                    if (result instanceof String s && s.matches("\\d+\\.\\d+.*")) {
                                        return s;
                                    }
                                }
                                String str = version.toString();
                                if (str.matches("\\d+\\.\\d+.*")) return str;
                            }
                        }
                    }
                } catch (ClassNotFoundException ignored) {}
            }

            // Fallback: look for version string in the brand
            try {
                Class<?> mc = cl.loadClass("net.minecraft.client.Minecraft");
                for (java.lang.reflect.Field f : mc.getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        Object val = f.get(null);
                        if (val instanceof String s && s.matches("\\d+\\.\\d+\\.\\d+")) {
                            return s;
                        }
                    }
                }
            } catch (ClassNotFoundException ignored) {}

        } catch (Throwable t) {
            System.err.println("[barry] Version detection failed: " + t.getMessage());
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String... names) {
        for (String name : names) {
            try {
                Method m = cls.getMethod(name);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                return m;
            }
        }
        return null;
    }

    public static ClassLoader findMinecraftClassLoader() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            ClassLoader cl = t.getContextClassLoader();
            if (cl == null) continue;
            try {
                cl.loadClass("net.minecraft.client.Minecraft");
                return cl;
            } catch (ClassNotFoundException ignored) {}
        }
        // Try the system classloader
        try {
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            sys.loadClass("net.minecraft.client.Minecraft");
            return sys;
        } catch (ClassNotFoundException ignored) {}
        return null;
    }
}
