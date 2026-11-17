package barry.config;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class KeybindRegistry {

    public enum Action {
        // Canonical key codes are GLFW (1.21.x). Overlay translates to SDL
        // scancodes at runtime when the client uses SDL input (26.x).
        OVERLAY_TOGGLE("Toggle overlay", 96),      // GLFW_KEY_GRAVE_ACCENT (backtick)
        PANIC("Panic — hide everything", 261),     // GLFW_KEY_DELETE
        ESP_TOGGLE("Toggle ESP rendering", 267),   // GLFW_KEY_PAGE_DOWN
        PROFILE_CYCLE("Cycle profile", 266),       // GLFW_KEY_PAGE_UP
        PAUSE_TOGGLE("Pause bot", 269),            // GLFW_KEY_END
        CONFIG_DUMP("Dump config to stderr", 268), // GLFW_KEY_HOME
        FILTER_MENU("Toggle filter menu", 77),     // GLFW_KEY_M
        SETTINGS_MENU("Toggle key settings", 75),  // GLFW_KEY_K
        MENU_UP("Menu: move up", 265),             // GLFW_KEY_UP
        MENU_DOWN("Menu: move down", 264),         // GLFW_KEY_DOWN
        MENU_TOGGLE_ITEM("Menu: toggle / rebind", 262); // GLFW_KEY_RIGHT

        private final String description;
        private final int defaultKey;

        Action(String description, int defaultKey) {
            this.description = description;
            this.defaultKey = defaultKey;
        }

        public String description() { return description; }
        public int defaultKey() { return defaultKey; }
    }

    private static final KeybindRegistry INSTANCE = new KeybindRegistry();
    public static KeybindRegistry get() { return INSTANCE; }

    private final Map<Action, Integer> bindings = new ConcurrentHashMap<>();
    // Raw SDL-scancode overrides set by the in-game rebind menu (26.x SDL input).
    // When present, the Overlay polls this scancode directly instead of
    // translating the default GLFW key.
    private final Map<Action, Integer> sdlOverrides = new ConcurrentHashMap<>();

    private KeybindRegistry() {
        for (Action a : Action.values()) {
            bindings.put(a, a.defaultKey);
        }
    }

    public Integer sdlOverride(Action action) { return sdlOverrides.get(action); }
    public void setSdlOverride(Action action, int scancode) { sdlOverrides.put(action, scancode); }
    public void clearSdlOverride(Action action) { sdlOverrides.remove(action); }

    public int keyFor(Action action) {
        return bindings.getOrDefault(action, action.defaultKey);
    }

    public boolean bind(Action action, int glfwKey) {
        // Check for conflicts: two actions on one key refuses the second
        for (Map.Entry<Action, Integer> entry : bindings.entrySet()) {
            if (entry.getValue() == glfwKey && entry.getKey() != action) {
                System.err.println("[barry] Key " + glfwKey + " already bound to "
                    + entry.getKey().name() + " (" + entry.getKey().description()
                    + "). Unbind it first.");
                return false;
            }
        }
        bindings.put(action, glfwKey);
        return true;
    }

    public void unbind(Action action) {
        bindings.remove(action);
    }

    public void resetDefaults() {
        bindings.clear();
        for (Action a : Action.values()) {
            bindings.put(a, a.defaultKey);
        }
    }

    public Map<Action, Integer> allBindings() {
        return Collections.unmodifiableMap(bindings);
    }

    public Optional<Action> actionForKey(int glfwKey) {
        for (Map.Entry<Action, Integer> entry : bindings.entrySet()) {
            if (entry.getValue() == glfwKey) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    public void dump() {
        System.err.println("[barry] Keybindings:");
        for (Action a : Action.values()) {
            Integer key = bindings.get(a);
            System.err.println("  " + a.name() + " = " + (key != null ? key : "(unbound)")
                + " — " + a.description());
        }
    }
}
