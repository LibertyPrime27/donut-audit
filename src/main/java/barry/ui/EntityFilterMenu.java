package barry.ui;

import barry.esp.EntityTypes;

import java.util.ArrayList;
import java.util.List;

// Entity selector sub-screen (Menu.Screen.ENTITIES). Mirrors FilterMenu but over
// the full entity-type registry. Type-to-search filters the list; UP/DOWN move;
// Enter (or RIGHT) toggles the selected type. State only — RenderHook draws the
// panel and Overlay polls input. Toggling flips EntityTypes enabled membership;
// the EntityTracker scan filters on it so changes take effect immediately.
public final class EntityFilterMenu {

    private static volatile int selected;
    private static volatile String query = "";

    private EntityFilterMenu() {}

    public static boolean isOpen() { return Menu.screen() == Menu.Screen.ENTITIES; }
    public static String query() { return query; }

    public static void reset() { selected = 0; query = ""; }

    // Entity type names matching the current search (all when the query is empty).
    public static List<String> visible() {
        List<String> out = new ArrayList<>();
        String q = query.toLowerCase();
        for (String name : EntityTypes.allNames()) {
            if (q.isEmpty() || name.toLowerCase().contains(q)) out.add(name);
        }
        return out;
    }

    public static void up() {
        int n = visible().size();
        if (n == 0) return;
        selected = (selected - 1 + n) % n;
    }

    public static void down() {
        int n = visible().size();
        if (n == 0) return;
        selected = (selected + 1) % n;
    }

    public static void toggleSelected() {
        List<String> vis = visible();
        if (vis.isEmpty()) return;
        if (selected >= vis.size()) selected = vis.size() - 1;
        EntityTypes.toggle(vis.get(selected));
    }

    public static int selected() { return selected; }

    // --- search input (fed by Overlay's key polling) ---
    public static void appendChar(char c) {
        query = query + c;
        selected = 0;
    }

    public static void backspace() {
        if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
        selected = 0;
    }
}
