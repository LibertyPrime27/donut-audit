package barry.ui;

import barry.core.OreType;

// In-game block filter. A keyboard-navigated list of ore types the overlay can
// show/hide. State only — the panel is drawn by RenderHook and input is polled
// by Overlay. Toggling an ore flips OreType.enabled; scanning keeps recording
// everything, so re-enabling an ore shows it instantly.
public final class FilterMenu {

    private static volatile boolean open;
    private static volatile int selected;

    private FilterMenu() {}

    public static boolean isOpen() { return open; }

    public static void toggleOpen() {
        open = !open;
        if (open) clamp();
    }

    public static void close() { open = false; }

    public static void up() {
        if (!open) return;
        int n = count();
        selected = (selected - 1 + n) % n;
    }

    public static void down() {
        if (!open) return;
        int n = count();
        selected = (selected + 1) % n;
    }

    public static void toggleSelected() {
        if (!open) return;
        clamp();
        OreType.values()[selected].toggleEnabled();
    }

    public static int selected() { return selected; }

    private static int count() { return OreType.values().length; }

    private static void clamp() {
        int n = count();
        if (selected < 0) selected = 0;
        if (selected >= n) selected = n - 1;
    }
}
