package barry.ui;

// Nested menu state machine. F6 opens the MAIN screen; from there the user
// enters the block selector, toggles fullbright, or opens the keybind editor.
// Overlay routes input and RenderHook draws based on the current screen.
public final class Menu {

    public enum Screen { NONE, MAIN, BLOCKS, KEYS }

    public static final String[] MAIN_ITEMS = { "Block Selector", "Fullbright", "Player ESP", "Keybinds" };

    private static volatile Screen screen = Screen.NONE;
    private static volatile int mainSel;

    private Menu() {}

    public static Screen screen() { return screen; }
    public static boolean isOpen() { return screen != Screen.NONE; }
    public static int mainSel() { return mainSel; }

    public static void toggleMain() {
        screen = (screen == Screen.NONE) ? Screen.MAIN : Screen.NONE;
    }

    public static void close() { screen = Screen.NONE; }

    // Back: submenu -> MAIN, MAIN -> closed.
    public static void back() {
        screen = (screen == Screen.BLOCKS || screen == Screen.KEYS) ? Screen.MAIN : Screen.NONE;
    }

    public static void mainUp() {
        if (screen == Screen.MAIN) mainSel = (mainSel - 1 + MAIN_ITEMS.length) % MAIN_ITEMS.length;
    }

    public static void mainDown() {
        if (screen == Screen.MAIN) mainSel = (mainSel + 1) % MAIN_ITEMS.length;
    }

    public static void mainSelect() {
        if (screen != Screen.MAIN) return;
        switch (mainSel) {
            case 0: FilterMenu.reset(); screen = Screen.BLOCKS; break;
            case 1: Fullbright.toggle(); break;
            case 2: barry.esp.PlayerTracker.toggle(); break;
            case 3: screen = Screen.KEYS; break;
            default: break;
        }
    }
}
