package barry.ui;

// Nested menu state machine. F6 opens the MAIN screen; from there the user
// enters the block selector, toggles fullbright, or opens the keybind editor.
// Overlay routes input and RenderHook draws based on the current screen.
public final class Menu {

    public enum Screen { NONE, MAIN, BLOCKS, ENTITIES, KEYS }

    public static final String[] MAIN_ITEMS = { "Block Selector", "Fullbright", "Player ESP", "Entities", "Keybinds", "Nether Auto" };

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

    // Back: submenu -> MAIN, MAIN -> closed. Also closes Nether Auto if open.
    public static void back() {
        if (netherScreen != NetherScreen.NONE) {
            netherScreen = NetherScreen.NONE;
            return;
        }
        screen = (screen == Screen.BLOCKS || screen == Screen.ENTITIES || screen == Screen.KEYS)
                ? Screen.MAIN : Screen.NONE;
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
            case 2: barry.esp.EntityTypes.toggle("player"); break;
            case 3: EntityFilterMenu.reset(); screen = Screen.ENTITIES; break;
            case 4: screen = Screen.KEYS; break;
            case 5: toggleNetherAuto(); break;
            default: break;
        }
    }

    // === Nether Auto submenu ===

    public enum NetherScreen { NONE, MAIN, OPTIONS }

    private static volatile NetherScreen netherScreen = NetherScreen.NONE;
    private static volatile int netherSel;

    public static NetherScreen netherScreen() { return netherScreen; }
    public static boolean isNetherOpen() { return netherScreen != NetherScreen.NONE; }
    public static int netherSel() { return netherSel; }

    private static void toggleNetherAuto() {
        if (netherScreen == NetherScreen.NONE) {
            netherScreen = NetherScreen.MAIN;
            netherSel = 0;
        } else {
            netherScreen = NetherScreen.NONE;
        }
    }

    public static void netherUp() {
        if (netherScreen == NetherScreen.NONE) return;
        int n = NetherOptions.ITEMS.length;
        netherSel = (netherSel - 1 + n) % n;
    }

    public static void netherDown() {
        if (netherScreen == NetherScreen.NONE) return;
        int n = NetherOptions.ITEMS.length;
        netherSel = (netherSel + 1) % n;
    }

    public static void netherSelect() {
        if (netherScreen != NetherScreen.MAIN) return;
        NetherOptions.toggleSelected();
    }

    public static void netherBack() {
        if (netherScreen == NetherScreen.OPTIONS) {
            netherScreen = NetherScreen.MAIN;
            netherSel = 0;
        } else {
            netherScreen = NetherScreen.NONE;
        }
    }

    // === Nether Auto option list ===

    public static final class NetherOptions {

        public enum Kind { TOGGLE, OPTION, ACTION };

        public static final String[] ITEMS = {
            "Start Auto Netherite",
            "--- Options ---",
            "Auto RTP",
            "Stay In Area",
            "Keep Searching",
            "Bright Debris ESP",
            "--- Disconnect ---",
            "Disconnect On Find",
            "Health Check Disconnect",
            "Health Threshold: 6",
        };

        private NetherOptions() {}

        public static void toggleSelected() {
            switch (netherSel) {
                case 0: {
                    boolean wasEnabled = NetheriteAutoMode.isEnabled();
                    NetheriteAutoMode.toggleEnabled();
                    // Close menu after enabling/disabling so user gets a clear view.
                    if (!wasEnabled && NetheriteAutoMode.isEnabled()) {
                        // Just enabled — close submenu, keep overlay on.
                        netherScreen = NetherScreen.NONE;
                    } else if (wasEnabled && !NetheriteAutoMode.isEnabled()) {
                        // Just disabled — close submenu.
                        netherScreen = NetherScreen.NONE;
                    }
                    break;
                }
                case 2: NetheriteAutoMode.toggleAutoRtp(); break;
                case 3: NetheriteAutoMode.toggleStayInArea(); break;
                case 4: NetheriteAutoMode.toggleKeepSearching(); break;
                case 5: NetheriteAutoMode.toggleBrightDebrisEsp(); break;
                case 7: NetheriteAutoMode.toggleDisconnectOnFind(); break;
                case 8: NetheriteAutoMode.toggleHealthCheckDisconnect(); break;
                case 9: {
                    int v = NetheriteAutoMode.getHealthThreshold();
                    v = (v >= 20) ? 1 : v + 1;
                    NetheriteAutoMode.setHealthThreshold(v);
                    break;
                }
            }
        }

        public static String selectedItemLabel() {
            return ITEMS[Math.max(0, Math.min(netherSel, ITEMS.length - 1))];
        }
    }
}
