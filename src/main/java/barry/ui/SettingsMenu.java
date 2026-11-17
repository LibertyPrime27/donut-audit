package barry.ui;

import barry.config.KeybindRegistry.Action;

// In-game key rebind panel. Lists the rebindable actions and their current key.
// RIGHT begins capture; the next key pressed becomes the new binding (stored as
// a raw SDL scancode override). State only — Overlay polls input and performs
// the capture; RenderHook draws the panel.
public final class SettingsMenu {

    // The actions a user can rebind from the panel (dev-only probe excluded).
    public static final Action[] ACTIONS = {
        Action.OVERLAY_TOGGLE,
        Action.PANIC,
        Action.FILTER_MENU,
        Action.MENU_UP,
        Action.MENU_DOWN,
        Action.MENU_TOGGLE_ITEM,
    };

    private static volatile int selected;
    private static volatile boolean capturing;

    private SettingsMenu() {}

    public static boolean isOpen() { return Menu.screen() == Menu.Screen.KEYS; }
    public static boolean isCapturing() { return capturing && isOpen(); }
    public static int selected() { return selected; }

    public static Action selectedAction() {
        return ACTIONS[Math.max(0, Math.min(selected, ACTIONS.length - 1))];
    }

    public static void up() {
        if (capturing) return;
        int n = ACTIONS.length;
        selected = (selected - 1 + n) % n;
    }

    public static void down() {
        if (capturing) return;
        int n = ACTIONS.length;
        selected = (selected + 1) % n;
    }

    public static void beginCapture() { capturing = true; }
    public static void endCapture() { capturing = false; }
}
