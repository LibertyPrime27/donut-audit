package barry.ui;

// Gate for swallowing Minecraft's keyboard handling while one of our menus is
// open. GuiTransformer injects a call to shouldConsume() at the head of
// KeyboardHandler.keyPress / charTyped / textInput; when it returns true those
// methods return immediately, so keystrokes don't open chat/inventory, move the
// player, or leak into any text field. Our menus still read keys via SDL polling
// (Overlay), so navigation and type-to-search keep working.
public final class InputCapture {

    private InputCapture() {}

    private static volatile boolean loggedOnce;

    public static boolean shouldConsume() {
        try {
            boolean c = Menu.isOpen();
            if (c && !loggedOnce) {
                loggedOnce = true;
                System.err.println("[barry] InputCapture: consuming keys (menu open)");
            }
            return c;
        } catch (Throwable t) {
            return false; // never break the game's input path
        }
    }
}
