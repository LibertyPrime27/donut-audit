package barry.ui;

import barry.core.OreType;

import java.util.ArrayList;
import java.util.List;

// In-game block filter with a type-to-search box. A keyboard-navigated list of
// tracked blocks the overlay can show/hide. Typing filters the list by name;
// UP/DOWN move; Enter (or RIGHT) toggles the selected block. State only — the
// panel is drawn by RenderHook and input is polled by Overlay. Toggling flips
// OreType.enabled; scanning keeps recording everything, so re-enabling shows it
// instantly.
public final class FilterMenu {

    private static volatile boolean open;
    private static volatile int selected;
    private static volatile String query = "";

    private FilterMenu() {}

    public static boolean isOpen() { return open; }
    public static String query() { return query; }

    public static void toggleOpen() {
        open = !open;
        if (open) { selected = 0; query = ""; }
    }

    public static void close() { open = false; }

    // Blocks matching the current search (all when the query is empty).
    public static List<OreType> visible() {
        List<OreType> out = new ArrayList<>();
        String q = query.toLowerCase();
        for (OreType o : OreType.values()) {
            if (q.isEmpty() || o.blockName().toLowerCase().contains(q)) out.add(o);
        }
        return out;
    }

    public static void up() {
        if (!open) return;
        int n = visible().size();
        if (n == 0) return;
        selected = (selected - 1 + n) % n;
    }

    public static void down() {
        if (!open) return;
        int n = visible().size();
        if (n == 0) return;
        selected = (selected + 1) % n;
    }

    public static void toggleSelected() {
        if (!open) return;
        List<OreType> vis = visible();
        if (vis.isEmpty()) return;
        if (selected >= vis.size()) selected = vis.size() - 1;
        vis.get(selected).toggleEnabled();
    }

    public static int selected() { return selected; }

    // --- search input (fed by Overlay's key polling) ---
    public static void appendChar(char c) {
        if (!open) return;
        query = query + c;
        selected = 0;
    }

    public static void backspace() {
        if (!open) return;
        if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
        selected = 0;
    }

    public static void clearQuery() {
        query = "";
        selected = 0;
    }
}
