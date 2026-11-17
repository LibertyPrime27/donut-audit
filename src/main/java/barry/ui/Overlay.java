package barry.ui;

import barry.Bootstrap;
import barry.attach.Remap;
import barry.config.KeybindRegistry;
import barry.config.KeybindRegistry.Action;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Overlay {

    private static final AtomicBoolean VISIBLE = new AtomicBoolean(false);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static volatile Thread tickThread;
    private static volatile Object minecraftInstance;
    private static volatile ClassLoader gameClassLoader;

    // Key debounce state
    private static volatile boolean lastToggleState = false;
    private static volatile boolean lastPanicState = false;

    private Overlay() {}

    public static void init(Instrumentation inst) {
        if (!INITIALIZED.compareAndSet(false, true)) return;

        gameClassLoader = Bootstrap.findMinecraftClassLoader();
        if (gameClassLoader == null) {
            System.err.println("[barry] No game classloader found. Overlay running headless.");
            return;
        }

        tickThread = new Thread(Overlay::tickLoop, "JIT Compilation Thread-0");
        tickThread.setDaemon(true);
        tickThread.setPriority(Thread.MIN_PRIORITY);
        tickThread.start();

        System.err.println("[barry] Overlay initialized. INSERT=toggle, DELETE=panic.");
    }

    public static void shutdown() {
        INITIALIZED.set(false);
        VISIBLE.set(false);
        if (tickThread != null) {
            tickThread.interrupt();
            tickThread = null;
        }
        minecraftInstance = null;
        gameClassLoader = null;
        System.err.println("[barry] Overlay shutdown.");
    }

    public static boolean isVisible() {
        return VISIBLE.get();
    }

    public static void setVisible(boolean visible) {
        VISIBLE.set(visible);
    }

    public static void panic() {
        VISIBLE.set(false);
    }

    private static void tickLoop() {
        while (INITIALIZED.get()) {
            try {
                tick();
                Thread.sleep(50);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                // fail closed: hide on error
                VISIBLE.set(false);
            }
        }
    }

    private static void tick() {
        if (gameClassLoader == null) return;

        // Poll keyboard state via LWJGL or AWT
        try {
            KeybindRegistry kb = KeybindRegistry.get();
            boolean panicDown = isKeyPressed(kb.keyFor(Action.PANIC));
            boolean toggleDown = isKeyPressed(kb.keyFor(Action.OVERLAY_TOGGLE));

            // Panic fires on key-down, even when overlay is closed
            if (panicDown && !lastPanicState) {
                panic();
                lastPanicState = true;
                return;
            }
            lastPanicState = panicDown;

            // Toggle fires on key-down edge
            if (toggleDown && !lastToggleState) {
                VISIBLE.set(!VISIBLE.get());
            }
            lastToggleState = toggleDown;
        } catch (Throwable ignored) {}

        // If visible, schedule render work on the game thread
        if (VISIBLE.get()) {
            renderOverlay();
        }
    }

    // All key codes are GLFW constants (from KeybindRegistry).
    private static boolean isKeyPressed(int glfwKeyCode) {
        try {
            Class<?> glfwClass = gameClassLoader.loadClass("org.lwjgl.glfw.GLFW");
            Object mc = getMinecraftInstance();
            if (mc == null) return false;

            Object window = findWindowHandle(mc);
            if (window instanceof Long handle) {
                Method getKey = glfwClass.getMethod("glfwGetKey", long.class, int.class);
                Object result = getKey.invoke(null, handle, glfwKeyCode);
                return result instanceof Integer i && i == 1; // GLFW_PRESS
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private static Object getMinecraftInstance() {
        if (minecraftInstance != null) return minecraftInstance;
        try {
            String className = Remap.resolveClass("net.minecraft.client.Minecraft");
            Class<?> mcClass = gameClassLoader.loadClass(className);

            // Try getInstance() / getMinecraft() or static INSTANCE field
            for (Method m : mcClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == mcClass
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    Object inst = m.invoke(null);
                    if (inst != null) {
                        minecraftInstance = inst;
                        return inst;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object findWindowHandle(Object mc) {
        try {
            // Modern MC: mc.getWindow().getWindow() returns the GLFW handle
            String windowClass = Remap.resolveClass("net.minecraft.client.Window");

            for (Field f : mc.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(mc);
                    if (val == null) continue;
                    if (val.getClass().getName().equals(windowClass)
                            || val.getClass().getSimpleName().contains("Window")) {
                        // Look for a long field (the GLFW handle)
                        for (Field wf : val.getClass().getDeclaredFields()) {
                            if (wf.getType() == long.class) {
                                wf.setAccessible(true);
                                long handle = wf.getLong(val);
                                if (handle != 0) return handle;
                            }
                        }
                        // Try getWindow() method
                        for (Method wm : val.getClass().getDeclaredMethods()) {
                            if (wm.getReturnType() == long.class && wm.getParameterCount() == 0) {
                                wm.setAccessible(true);
                                long handle = (long) wm.invoke(val);
                                if (handle != 0) return handle;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void renderOverlay() {
        // M0: static text panel proving the overlay works.
        // Actual ESP rendering goes in barry.render.EspRenderer (M1/P2).
        // For now, we schedule a debug overlay draw on the game's main thread.
        //
        // The real render path will hook into GameRenderer.render() via a
        // ClassFileTransformer, injecting a callback at the end of the frame.
        // That hook is M1 work. Here we just prove the tick loop and key
        // polling work.
    }
}
