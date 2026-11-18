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
    private static volatile boolean lastMenuState = false;
    private static volatile boolean lastSettingsState = false;
    private static volatile boolean lastMenuUp = false;
    private static volatile boolean lastMenuDown = false;
    private static volatile boolean lastMenuToggleItem = false;
    private static volatile boolean lastEsc = false;
    private static volatile boolean diagLogged = false;

    // Rebind capture: snapshot of keys held when capture began (ignored until
    // released) so we don't grab the key that started the capture.
    private static volatile boolean[] heldSnapshot;
    // Per-tick key snapshot for the filter search box (edge-detect typed keys).
    private static volatile boolean[] searchPrev;

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
        barry.esp.PlayerTracker.reset();
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
        Menu.close();
        Fullbright.set(false); // revert to the vanilla look on panic
        barry.esp.PlayerTracker.setEnabled(false);
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
            boolean panicDown = isActionPressed(Action.PANIC);
            boolean toggleDown = isActionPressed(Action.OVERLAY_TOGGLE);

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
                if (!now) Menu.close(); // hide menus when overlay goes off
                System.err.println("[barry] Overlay " + (now ? "ON" : "OFF"));
            }
            lastToggleState = toggleDown;

            // HOME triggers the one-shot block-source probe (version discovery).
            boolean probeDown = isActionPressed(Action.CONFIG_DUMP);
            if (probeDown && !lastProbeState) {
                barry.xray.BlockSourceProbe.dumpOnce();
            }
            lastProbeState = probeDown;

            // --- Nested menu (F6 opens MAIN) ---
            boolean menuKey = isActionPressed(Action.FILTER_MENU);
            if (menuKey && !lastMenuState && VISIBLE.get()) {
                Menu.toggleMain();
                System.err.println("[barry] Menu " + Menu.screen()
                        + " (F6 scancode=" + effectiveScancode(Action.FILTER_MENU) + ")");
            }
            lastMenuState = menuKey;

            if (Menu.isOpen()) {
                boolean up = isActionPressed(Action.MENU_UP);
                boolean dn = isActionPressed(Action.MENU_DOWN);
                // Activate = RIGHT (bindable) OR LEFT OR ENTER — all select/toggle.
                boolean tg = isActionPressed(Action.MENU_TOGGLE_ITEM) || sdlPressed(80) || sdlPressed(40);
                boolean upEdge = up && !lastMenuUp;
                boolean dnEdge = dn && !lastMenuDown;
                boolean tgEdge = tg && !lastMenuToggleItem;

                if (!SettingsMenu.isCapturing()) {
                    switch (Menu.screen()) {
                        case MAIN:
                            if (upEdge) Menu.mainUp();
                            if (dnEdge) Menu.mainDown();
                            if (tgEdge) Menu.mainSelect();
                            break;
                        case BLOCKS:
                            if (upEdge) FilterMenu.up();
                            if (dnEdge) FilterMenu.down();
                            if (tgEdge) FilterMenu.toggleSelected();
                            break;
                        case KEYS:
                            if (upEdge) SettingsMenu.up();
                            if (dnEdge) SettingsMenu.down();
                            if (tgEdge) { SettingsMenu.beginCapture(); captureStart(); }
                            break;
                        default: break;
                    }
                }
                lastMenuUp = up; lastMenuDown = dn; lastMenuToggleItem = tg;

                // Esc backs out: cancel a capture, else submenu -> MAIN -> closed.
                boolean esc = sdlPressed(41);
                if (esc && !lastEsc) {
                    if (SettingsMenu.isCapturing()) SettingsMenu.endCapture();
                    else Menu.back();
                }
                lastEsc = esc;
            } else {
                lastMenuUp = lastMenuDown = lastMenuToggleItem = false;
                lastEsc = false;
            }

            // Block selector search box (only on the BLOCKS screen): type to
            // filter, Backspace to delete, Enter to toggle. The input-capture hook
            // keeps these keys out of the game while the menu is open.
            if (Menu.screen() == Menu.Screen.BLOCKS) {
                pollSearchInput();
            } else {
                searchPrev = null;
            }

            // Rebind capture: the first newly-pressed key wins (Esc handled above).
            if (SettingsMenu.isCapturing()) {
                int sc = captureScan();
                if (sc >= 0 && sc != 41) {
                    Action a = SettingsMenu.selectedAction();
                    kb.setSdlOverride(a, sc);
                    SettingsMenu.endCapture();
                    System.err.println("[barry] rebound " + a.name() + " -> " + scancodeName(sc) + " (SDL " + sc + ")");
                }
            }

            // Keep fullbright asserted while enabled.
            Fullbright.apply();
        } catch (Throwable ignored) {}

        // Player ESP scan (runs at its own 10 Hz internal rate).
        if (VISIBLE.get()) {
            barry.esp.PlayerTracker.scan();
        }

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
            case 77:  return 16; // M
            case 75:  return 14; // K
            case 295: return 63; // F6 (filter menu)
            case 296: return 64; // F7 (settings menu)
            case 265: return 82; // UP arrow
            case 264: return 81; // DOWN arrow
            case 262: return 79; // RIGHT arrow
            case 263: return 80; // LEFT arrow
            case 257: return 40; // ENTER
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

    // Poll an action, honoring a raw SDL-scancode override set via the rebind menu.
    private static boolean isActionPressed(Action a) {
        if (backend == Backend.UNKNOWN) backend = detectBackend();
        try {
            if (backend == Backend.SDL) return sdlPressed(effectiveScancode(a));
            if (backend == Backend.GLFW) return glfwPressed(KeybindRegistry.get().keyFor(a));
        } catch (Throwable t) {
            if (!diagLogged) { System.err.println("[barry] diag: isActionPressed error: " + t); diagLogged = true; }
        }
        return false;
    }

    // Effective SDL scancode for an action: the override if set, else the
    // translation of the default GLFW binding.
    private static int effectiveScancode(Action a) {
        Integer ov = KeybindRegistry.get().sdlOverride(a);
        return (ov != null) ? ov : glfwToSdlScancode(KeybindRegistry.get().keyFor(a));
    }

    // Public label for a key, for the settings panel.
    public static String keyLabel(Action a) {
        int sc = effectiveScancode(a);
        return sc < 0 ? "(unset)" : scancodeName(sc);
    }

    // --- rebind capture (SDL) ---
    private static java.nio.ByteBuffer keyboardBuffer() throws Exception {
        if (cachedGetKeyboardState == null) {
            Class<?> sdlKb = findSdlKeyboardClass();
            if (sdlKb == null) return null;
            cachedGetKeyboardState = sdlKb.getMethod("SDL_GetKeyboardState");
        }
        Object buf = cachedGetKeyboardState.invoke(null);
        return (buf instanceof java.nio.ByteBuffer bb) ? bb : null;
    }

    private static void captureStart() {
        try {
            java.nio.ByteBuffer bb = keyboardBuffer();
            if (bb == null) { heldSnapshot = null; return; }
            boolean[] snap = new boolean[bb.limit()];
            for (int i = 0; i < bb.limit(); i++) snap[i] = bb.get(i) != 0;
            heldSnapshot = snap;
        } catch (Throwable ignored) { heldSnapshot = null; }
    }

    // Returns the first scancode that is newly pressed (not held when capture
    // began), or -1. Keys held at start become eligible once released.
    private static int captureScan() {
        try {
            java.nio.ByteBuffer bb = keyboardBuffer();
            if (bb == null) return -1;
            boolean[] snap = heldSnapshot;
            for (int i = 4; i < bb.limit(); i++) {
                boolean down = bb.get(i) != 0;
                if (down) {
                    if (i == 41) continue; // Esc handled as cancel
                    if (snap != null && i < snap.length && snap[i]) continue; // still-held start key
                    return i;
                } else if (snap != null && i < snap.length) {
                    snap[i] = false; // released -> now eligible
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    // Edge-detect newly pressed keys and drive the filter search box.
    private static void pollSearchInput() {
        try {
            java.nio.ByteBuffer bb = keyboardBuffer();
            if (bb == null) return;
            boolean[] prev = searchPrev;
            for (int sc = 4; sc < bb.limit(); sc++) {
                boolean down = bb.get(sc) != 0;
                boolean was = prev != null && sc < prev.length && prev[sc];
                if (down && !was) handleSearchKey(sc);
            }
            boolean[] snap = new boolean[bb.limit()];
            for (int i = 0; i < bb.limit(); i++) snap[i] = bb.get(i) != 0;
            searchPrev = snap;
        } catch (Throwable ignored) {}
    }

    private static void handleSearchKey(int sc) {
        if (sc >= 4 && sc <= 29) { FilterMenu.appendChar((char) ('a' + (sc - 4))); return; }
        if (sc >= 30 && sc <= 38) { FilterMenu.appendChar((char) ('1' + (sc - 30))); return; }
        switch (sc) {
            case 39: FilterMenu.appendChar('0'); break;
            case 42: FilterMenu.backspace(); break;      // Backspace
            // Enter (toggle) and Esc (back) handled in the nav block; arrows/F6 elsewhere.
            default: break;
        }
    }

    private static String scancodeName(int sc) {
        if (sc >= 4 && sc <= 29) return String.valueOf((char) ('A' + (sc - 4)));
        if (sc >= 30 && sc <= 38) return String.valueOf((char) ('1' + (sc - 30)));
        if (sc == 39) return "0";
        if (sc >= 58 && sc <= 69) return "F" + (sc - 57);
        switch (sc) {
            case 40: return "Enter";  case 41: return "Esc";    case 42: return "Backspace";
            case 43: return "Tab";    case 44: return "Space";  case 45: return "-";
            case 46: return "=";      case 47: return "[";      case 48: return "]";
            case 49: return "\\";     case 51: return ";";      case 52: return "'";
            case 53: return "`";      case 54: return ",";      case 55: return ".";
            case 56: return "/";      case 57: return "CapsLock";
            case 73: return "Insert"; case 74: return "Home";   case 75: return "PageUp";
            case 76: return "Delete"; case 77: return "End";    case 78: return "PageDown";
            case 79: return "Right";  case 80: return "Left";   case 81: return "Down";
            case 82: return "Up";
            case 224: return "LCtrl"; case 225: return "LShift";case 226: return "LAlt";
            case 228: return "RCtrl"; case 229: return "RShift";case 230: return "RAlt";
            default: return "SDL#" + sc;
        }
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
