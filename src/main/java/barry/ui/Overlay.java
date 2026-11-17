package barry.ui;

import barry.Bootstrap;
import barry.config.KeybindRegistry;
import barry.config.KeybindRegistry.Action;
import barry.render.RenderHook;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Overlay {

    private static final AtomicBoolean VISIBLE = new AtomicBoolean(false);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static volatile Thread tickThread;
    private static volatile ClassLoader gameClassLoader;

    // Key debounce state
    private static volatile boolean lastToggleState = false;
    private static volatile boolean lastPanicState = false;
    private static volatile boolean lastProbeState = false;
    private static volatile boolean diagLogged = false;

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

        System.err.println("[barry] Overlay initialized. BACKTICK=toggle, DELETE=panic.");
    }

    public static void shutdown() {
        INITIALIZED.set(false);
        VISIBLE.set(false);
        if (tickThread != null) {
            tickThread.interrupt();
            tickThread = null;
        }
        gameClassLoader = null;
        cachedGetKeyboardState = null;
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
                boolean now = !VISIBLE.get();
                VISIBLE.set(now);
                System.err.println("[barry] Overlay " + (now ? "ON" : "OFF"));
            }
            lastToggleState = toggleDown;

            // HOME triggers the one-shot block-source probe (version discovery).
            boolean probeDown = isKeyPressed(kb.keyFor(Action.CONFIG_DUMP));
            if (probeDown && !lastProbeState) {
                barry.xray.BlockSourceProbe.dumpOnce();
            }
            lastProbeState = probeDown;
        } catch (Throwable ignored) {}

        // If visible, schedule render work on the game thread
        if (VISIBLE.get()) {
            renderOverlay();
        }
    }

    // Input backend. 1.21.x uses GLFW; 26.x uses SDL3. Detected once.
    private enum Backend { UNKNOWN, GLFW, SDL, NONE }
    private static volatile Backend backend = Backend.UNKNOWN;

    // GLFW path
    private static volatile Method cachedGlfwGetKey;
    private static volatile Long cachedWindowHandle;
    // SDL path
    private static volatile Method cachedGetKeyboardState;

    // GLFW keycode -> SDL scancode, for the keys we bind.
    private static int glfwToSdlScancode(int glfw) {
        switch (glfw) {
            case 96:  return 53; // GRAVE
            case 261: return 76; // DELETE
            case 267: return 78; // PAGE_DOWN
            case 266: return 75; // PAGE_UP
            case 269: return 77; // END
            case 268: return 74; // HOME
            default:  return -1;
        }
    }

    private static boolean isKeyPressed(int glfwKey) {
        if (backend == Backend.UNKNOWN) backend = detectBackend();
        try {
            if (backend == Backend.GLFW) return glfwPressed(glfwKey);
            if (backend == Backend.SDL) return sdlPressed(glfwToSdlScancode(glfwKey));
        } catch (Throwable t) {
            if (!diagLogged) { System.err.println("[barry] diag: isKeyPressed error: " + t); diagLogged = true; }
        }
        return false;
    }

    private static Backend detectBackend() {
        // Prefer whichever input lib is actually loaded in the client.
        if (findLoadedClass("org.lwjgl.glfw.GLFW") != null) {
            System.err.println("[barry] Input backend: GLFW");
            return Backend.GLFW;
        }
        if (findSdlKeyboardClass() != null) {
            System.err.println("[barry] Input backend: SDL");
            return Backend.SDL;
        }
        return Backend.UNKNOWN; // keep retrying until a lib loads
    }

    private static Class<?> findLoadedClass(String fqcn) {
        try {
            var inst = Bootstrap.instrumentation();
            if (inst == null) return null;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (fqcn.equals(c.getName())) return c;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // --- GLFW (1.21.x) ---
    private static boolean glfwPressed(int glfwKey) throws Exception {
        if (cachedGlfwGetKey == null) {
            Class<?> glfw = gameClassLoader.loadClass("org.lwjgl.glfw.GLFW");
            cachedGlfwGetKey = glfw.getMethod("glfwGetKey", long.class, int.class);
        }
        if (cachedWindowHandle == null) {
            cachedWindowHandle = resolveWindowHandle();
            if (cachedWindowHandle == null) return false;
        }
        Object r = cachedGlfwGetKey.invoke(null, cachedWindowHandle.longValue(), glfwKey);
        return r instanceof Integer i && i == 1; // GLFW_PRESS
    }

    private static Long resolveWindowHandle() {
        try {
            Object mc = RenderHook.minecraftInstance();
            if (mc == null) return null;
            for (var f : mc.getClass().getDeclaredFields()) {
                if (f.getType().getSimpleName().contains("Window")) {
                    f.setAccessible(true);
                    Object win = f.get(mc);
                    if (win == null) continue;
                    // Window.getWindow() -> long handle, or a long field
                    for (Method wm : win.getClass().getDeclaredMethods()) {
                        if (wm.getReturnType() == long.class && wm.getParameterCount() == 0) {
                            wm.setAccessible(true);
                            long h = (long) wm.invoke(win);
                            if (h != 0) return h;
                        }
                    }
                    for (var wf : win.getClass().getDeclaredFields()) {
                        if (wf.getType() == long.class) {
                            wf.setAccessible(true);
                            long h = wf.getLong(win);
                            if (h != 0) return h;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // --- SDL (26.x) ---
    private static boolean sdlPressed(int scancode) throws Exception {
        if (scancode < 0) return false;
        if (cachedGetKeyboardState == null) {
            Class<?> sdlKb = findSdlKeyboardClass();
            if (sdlKb == null) return false;
            cachedGetKeyboardState = sdlKb.getMethod("SDL_GetKeyboardState");
        }
        Object buf = cachedGetKeyboardState.invoke(null);
        if (buf instanceof java.nio.ByteBuffer bb) {
            if (scancode >= bb.limit()) return false;
            return bb.get(scancode) != 0;
        }
        return false;
    }

    private static Class<?> findSdlKeyboardClass() {
        try {
            var inst = Bootstrap.instrumentation();
            if (inst == null) return null;
            ClassLoader sdlLoader = null;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String n = c.getName();
                if ("org.lwjgl.sdl.SDLKeyboard".equals(n)) return c;
                if (sdlLoader == null && n.startsWith("org.lwjgl.sdl.")) {
                    sdlLoader = c.getClassLoader();
                }
            }
            if (sdlLoader != null) {
                return Class.forName("org.lwjgl.sdl.SDLKeyboard", true, sdlLoader);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void renderOverlay() {
        // Rendering is driven by RenderHook.onGuiRender (ASM hook into Gui.render).
        // This tick loop only polls keys and flips the visible flag.
    }
}
