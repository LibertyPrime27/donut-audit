package barry.ui;

import barry.Bootstrap;
import barry.config.KeybindRegistry;
import barry.config.KeybindRegistry.Action;

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
        } catch (Throwable ignored) {}

        // If visible, schedule render work on the game thread
        if (VISIBLE.get()) {
            renderOverlay();
        }
    }

    private static volatile Method cachedGetKeyboardState;
    private static volatile boolean sdlNotLoadedLogged;

    // Find org.lwjgl.sdl.SDLKeyboard. The top-level class may not be loaded yet
    // (MC drives input via SDL events), so if a scan misses it we locate any
    // loaded org.lwjgl.sdl.* class and force-load SDLKeyboard via its loader.
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

    // MC 26.3 uses SDL3 for input. Key codes are SDL_Scancode values.
    // SDL_GetKeyboardState() returns a ByteBuffer indexed by scancode;
    // a nonzero byte means the key is currently down.
    private static boolean isKeyPressed(int scancode) {
        try {
            if (cachedGetKeyboardState == null) {
                Class<?> sdlKb = findSdlKeyboardClass();
                if (sdlKb == null) {
                    if (!sdlNotLoadedLogged) {
                        System.err.println("[barry] diag: SDLKeyboard not yet available, retrying");
                        sdlNotLoadedLogged = true;
                    }
                    return false;
                }
                cachedGetKeyboardState = sdlKb.getMethod("SDL_GetKeyboardState");
                System.err.println("[barry] SDL keyboard polling ready.");
            }
            Object buf = cachedGetKeyboardState.invoke(null);
            if (buf instanceof java.nio.ByteBuffer bb) {
                if (scancode < 0 || scancode >= bb.limit()) return false;
                return bb.get(scancode) != 0;
            }
        } catch (Throwable t) {
            if (!diagLogged) { System.err.println("[barry] diag: isKeyPressed error: " + t); diagLogged = true; }
        }
        return false;
    }

    private static void renderOverlay() {
        // Rendering is driven by RenderHook.onGuiRender (ASM hook into Gui.render).
        // This tick loop only polls keys and flips the visible flag.
    }
}
