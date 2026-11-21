package barry.render;

import barry.attach.GuiTransformer;
import barry.attach.Remap;
import barry.ui.Overlay;
import barry.xray.XrayEngine;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RenderHook {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile EspRenderer espRenderer;
    private static volatile HudRenderer hudRenderer;
    private static volatile XrayEngine engine;
    private static volatile Thread renderThread;

    private RenderHook() {}

    public static void init(XrayEngine eng, Instrumentation inst) {
        engine = eng;
        espRenderer = new EspRenderer(eng);
        hudRenderer = new HudRenderer(eng);

        // Start a render-sync thread that rebuilds the render list each frame
        // The actual rendering is done from the game's render thread via callback
        renderThread = new Thread(RenderHook::renderLoop, "C1 CompilerThread-0");
        renderThread.setDaemon(true);
        renderThread.setPriority(Thread.NORM_PRIORITY);
        renderThread.start();

        // Install the class transformer to hook into the render pipeline
        if (Remap.isReady()) {
            installTransformer(inst);
        }
        WorldRenderHook.init(eng);

        INSTALLED.set(true);
        System.err.println("[barry] RenderHook installed.");
    }

    public static void shutdown() {
        INSTALLED.set(false);
        WorldRenderHook.shutdown();
        if (renderThread != null) {
            renderThread.interrupt();
            renderThread = null;
        }
        espRenderer = null;
        hudRenderer = null;
        engine = null;
    }

    public static EspRenderer esp() { return espRenderer; }
    public static HudRenderer hud() { return hudRenderer; }

    // Package-visible accessor for the XrayEngine (used by Overlay's Nether Auto tick).
    public static barry.xray.XrayEngine engine() { return engine; }

    // Called from the injected hook in the game's render method
    // This runs on the render thread
    public static void onRenderWorld(Object poseStack, float partialTick) {
        if (!INSTALLED.get() || !Overlay.isVisible()) return;

        try {
            EspRenderer esp = espRenderer;
            if (esp == null || !esp.isEnabled()) return;

            // Get camera position
            double[] cam = getCameraPos();
            if (cam == null) return;

            esp.render(poseStack, null, cam[0], cam[1], cam[2]);
        } catch (Throwable ignored) {
            // Never crash the game's render path
        }
    }

    // Called after GUI rendering
    public static void onRenderHud(Object guiGraphics, float partialTick) {
        if (!INSTALLED.get() || !Overlay.isVisible()) return;

        try {
            HudRenderer hud = hudRenderer;
            if (hud != null) {
                hud.render(guiGraphics, partialTick);
            }
        } catch (Throwable ignored) {}
    }

    private static void renderLoop() {
        while (INSTALLED.get()) {
            try {
                if (Overlay.isVisible() && espRenderer != null) {
                    espRenderer.buildRenderList();
                }
                // Sync at ~60 FPS
                Thread.sleep(16);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable ignored) {}
        }
    }

    private static double[] getCameraPos() {
        try {
            ClassLoader cl = barry.Bootstrap.findMinecraftClassLoader();
            if (cl == null) return null;

            String mcClassName = Remap.resolveClass("net.minecraft.client.Minecraft");
            Class<?> mcClass = cl.loadClass(mcClassName);

            Object mc = null;
            for (Method m : mcClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == mcClass
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    mc = m.invoke(null);
                    break;
                }
            }
            if (mc == null) return null;

            // mc.gameRenderer.getMainCamera().getPosition()
            Object gameRenderer = findFieldByType(mc, "GameRenderer");
            if (gameRenderer == null) return null;

            Object camera = null;
            for (Method m : gameRenderer.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().contains("Camera")) {
                    m.setAccessible(true);
                    camera = m.invoke(gameRenderer);
                    break;
                }
            }
            if (camera == null) return null;

            // Camera.getPosition() -> Vec3
            for (Method m : camera.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().contains("Vec3")) {
                    m.setAccessible(true);
                    Object pos = m.invoke(camera);
                    if (pos != null) {
                        Field xf = pos.getClass().getDeclaredField("x");
                        Field yf = pos.getClass().getDeclaredField("y");
                        Field zf = pos.getClass().getDeclaredField("z");
                        xf.setAccessible(true); yf.setAccessible(true); zf.setAccessible(true);
                        return new double[]{xf.getDouble(pos), yf.getDouble(pos), zf.getDouble(pos)};
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Object findFieldByType(Object obj, String typeNameContains) {
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (f.getType().getSimpleName().contains(typeNameContains)) {
                try {
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static volatile Object cachedMc;
    private static volatile Object cachedFont;
    private static volatile Method cachedDraw;
    private static volatile Method cachedFill;
    private static volatile boolean drawShadow = true;

    // Shared accessor for the Minecraft singleton instance.
    public static Object minecraftInstance() {
        if (cachedMc != null) return cachedMc;
        try {
            ClassLoader cl = barry.Bootstrap.findMinecraftClassLoader();
            if (cl == null) return null;
            Class<?> mcClass = cl.loadClass(Remap.resolveClass("net.minecraft.client.Minecraft"));
            for (Method m : mcClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == mcClass
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    Object mc = m.invoke(null);
                    if (mc != null) { cachedMc = mc; return mc; }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // Injected hook target. The arg is either a GuiGraphics (1.21.x) or a
    // GuiGraphicsExtractor (26.x). We resolve the matching text call once and
    // draw the HUD lines each frame. On 26.x this fires from every Hud.extract*
    // method (overdraw at the same position); on 1.21.x it fires once per frame.
    public static void onGuiRender(Object graphics) {
        if (!INSTALLED.get() || !Overlay.isVisible()) return;
        try {
            if (cachedFont == null) cachedFont = resolveFont();
            if (cachedFont == null) return;

            if (cachedDraw == null) {
                cachedDraw = resolveDrawMethod(graphics);
                if (cachedDraw == null) return;
                drawShadow = cachedDraw.getParameterCount() == 6;
                System.err.println("[barry] HUD draw via " + cachedDraw.getName()
                        + " (" + cachedDraw.getParameterCount() + " args)");
            }

            // 3D ore outlines are rendered in the world (WorldRenderHook), not here.
            String[] lines = hudLines();
            int y = 4;
            for (int i = 0; i < lines.length; i++) {
                int color = i == 0 ? 0xFF55FF55 : (i == 1 ? 0xFFAAAAAA : 0xFFFFFFFF);
                drawStr(graphics, lines[i], 4, y, color);
                y += 10;
            }

            // Entity/player ESP HUD section
            if (barry.esp.EntityTypes.enabledCount() > 0) {
                var ents = barry.esp.EntityTracker.tracked();
                if (!ents.isEmpty()) {
                    y += 2;
                    drawStr(graphics, "ENTITIES (" + ents.size() + ")", 4, y, 0xFFFF6666);
                    y += 10;
                    int shown = 0;
                    for (var e : ents) {
                        if (shown >= 8) break;
                        String line = e.living()
                            ? String.format("  %-16s %dm  %.0f HP", e.label(), (int) e.distance(), e.health())
                            : String.format("  %-16s %dm", e.label(), (int) e.distance());
                        drawStr(graphics, line, 4, y, e.color());
                        y += 10;
                        shown++;
                    }
                    if (ents.size() > shown) {
                        drawStr(graphics, "  …+" + (ents.size() - shown) + " more", 4, y, 0xFF888888);
                    }
                }
            }

            // Menu panels (one screen at a time).
            if (Overlay.isVisible()) {
                switch (barry.ui.Menu.screen()) {
                    case MAIN:     drawMainMenu(graphics); break;
                    case BLOCKS:   drawFilterMenu(graphics); break;
                    case ENTITIES: drawEntityMenu(graphics); break;
                    case KEYS:     drawSettingsMenu(graphics); break;
                    default: break;
                }
            }

            // Nether Auto panel (drawn on top when open)
            if (barry.ui.Menu.isNetherOpen() && Overlay.isVisible()) {
                drawNetherMenu(graphics);
            }

            // Centered countdown overlay during RTP phase
            if (barry.ui.NetheriteAutoMode.isShowCountdown()) {
                drawCountdownMessage(graphics);
            }
        } catch (Throwable ignored) {}
    }

    private static void drawMainMenu(Object graphics) {
        String[] items = barry.ui.Menu.MAIN_ITEMS;
        int x = 140, top = 24, w = 220, rowH = 10;
        int h = (items.length + 2) * rowH + 8;
        fillRect(graphics, x - 6, top - 6, x + w, top + h, 0xC0101014);
        fillRect(graphics, x - 6, top - 6, x + w, top - 5, 0xFFFFCC33);
        fillRect(graphics, x - 6, top + h - 1, x + w, top + h, 0xFFFFCC33);

        int y = top;
        drawStr(graphics, "barry  F6:close  UP/DOWN:move  RIGHT:select", x, y, 0xFFFFCC33);
        y += rowH;
        int sel = barry.ui.Menu.mainSel();
        for (int i = 0; i < items.length; i++) {
            boolean selected = (i == sel);
            if (selected) fillRect(graphics, x - 4, y - 1, x + w - 2, y + rowH - 2, 0x40FFFFFF);
            int enBlocks = barry.core.OreType.enabledCount();
            int enEnts = barry.esp.EntityTypes.enabledCount();
            String label = items[i];
            boolean on = false;
            if (i == 0) { on = enBlocks > 0; label = label + " (" + enBlocks + "): " + (on ? "ON" : "OFF"); }
            if (i == 1) { on = barry.ui.Fullbright.isOn(); label = label + ": " + (on ? "ON" : "OFF"); }
            if (i == 2) { on = barry.esp.EntityTypes.isEnabled("player"); label = label + ": " + (on ? "ON" : "OFF"); }
            if (i == 3) { on = enEnts > 0; label = label + " (" + enEnts + "): " + (on ? "ON" : "OFF"); }
            if (i == 5) { on = barry.ui.NetheriteAutoMode.isEnabled(); label = label + ": " + (on ? "ON" : "OFF"); }
            String prefix = selected ? "> " : "  ";
            int col = selected ? 0xFFFFFFFF : 0xFFCCCCCC;
            if (on && !selected) col = (i == 2 || i == 3) ? 0xFFFF6666 : 0xFF66FF66;
            drawStr(graphics, prefix + label, x, y, col);
            y += rowH;
        }
    }

    private static void drawStr(Object graphics, String s, int x, int y, int color) {
        try {
            if (drawShadow) cachedDraw.invoke(graphics, cachedFont, s, x, y, color, true);
            else cachedDraw.invoke(graphics, cachedFont, s, x, y, color);
        } catch (Throwable ignored) {}
    }

    private static void fillRect(Object graphics, int x1, int y1, int x2, int y2, int argb) {
        try {
            if (cachedFill == null) cachedFill = resolveFillMethod(graphics);
            if (cachedFill != null) cachedFill.invoke(graphics, x1, y1, x2, y2, argb);
        } catch (Throwable ignored) {}
    }

    private static void drawFilterMenu(Object graphics) {
        java.util.List<barry.core.OreType> vis = barry.ui.FilterMenu.visible();
        String query = barry.ui.FilterMenu.query();
        int x = 140, top = 24, w = 230, rowH = 10;
        int rows = Math.max(vis.size(), 1);
        int h = (rows + 3) * rowH + 8;
        fillRect(graphics, x - 6, top - 6, x + w, top + h, 0xC0101014);
        fillRect(graphics, x - 6, top - 6, x + w, top - 5, 0xFF55FF55);
        fillRect(graphics, x - 6, top + h - 1, x + w, top + h, 0xFF55FF55);

        int y = top;
        drawStr(graphics, "BLOCKS  Esc:back  type:search  Space/Enter:toggle", x, y, 0xFF55FF55);
        y += rowH;
        // Search box line.
        String caret = ((System.currentTimeMillis() / 500) % 2 == 0) ? "_" : " ";
        drawStr(graphics, "search: " + query + caret, x, y, 0xFFFFFF88);
        y += rowH;

        int sel = barry.ui.FilterMenu.selected();
        if (vis.isEmpty()) {
            drawStr(graphics, "  (no blocks match)", x, y, 0xFF888888);
            return;
        }
        for (int i = 0; i < vis.size(); i++) {
            barry.core.OreType o = vis.get(i);
            boolean on = o.isEnabled();
            boolean selected = (i == sel);
            if (selected) fillRect(graphics, x - 4, y - 1, x + w - 2, y + rowH - 2, 0x40FFFFFF);
            String box = on ? "[x] " : "[ ] ";
            String prefix = selected ? "> " : "  ";
            int col = selected ? 0xFFFFFFFF : (on ? o.color() : 0xFF666666);
            drawStr(graphics, prefix + box + o.blockName(), x, y, col);
            y += rowH;
        }
    }

    private static void drawEntityMenu(Object graphics) {
        java.util.List<String> vis = barry.ui.EntityFilterMenu.visible();
        String query = barry.ui.EntityFilterMenu.query();
        int x = 140, top = 24, w = 240, rowH = 10;
        final int WINDOW = 14; // visible rows (list can be ~140 long)
        int rows = Math.min(Math.max(vis.size(), 1), WINDOW);
        int h = (rows + 3) * rowH + 8;
        fillRect(graphics, x - 6, top - 6, x + w, top + h, 0xC0101014);
        fillRect(graphics, x - 6, top - 6, x + w, top - 5, 0xFFFF9955);
        fillRect(graphics, x - 6, top + h - 1, x + w, top + h, 0xFFFF9955);

        int y = top;
        drawStr(graphics, "ENTITIES  Esc:back  type:search  Space/Enter:toggle", x, y, 0xFFFF9955);
        y += rowH;
        String caret = ((System.currentTimeMillis() / 500) % 2 == 0) ? "_" : " ";
        drawStr(graphics, "search: " + query + caret, x, y, 0xFFFFFF88);
        y += rowH;

        int sel = barry.ui.EntityFilterMenu.selected();
        if (vis.isEmpty()) {
            drawStr(graphics, "  (no entities match)", x, y, 0xFF888888);
            return;
        }
        // Scroll window so the selected row stays visible.
        int start = Math.max(0, Math.min(sel - WINDOW / 2, vis.size() - WINDOW));
        if (start < 0) start = 0;
        int end = Math.min(vis.size(), start + WINDOW);
        for (int i = start; i < end; i++) {
            String name = vis.get(i);
            boolean on = barry.esp.EntityTypes.isEnabled(name);
            boolean selected = (i == sel);
            if (selected) fillRect(graphics, x - 4, y - 1, x + w - 2, y + rowH - 2, 0x40FFFFFF);
            String box = on ? "[x] " : "[ ] ";
            String prefix = selected ? "> " : "  ";
            int col = selected ? 0xFFFFFFFF : (on ? barry.esp.EntityTypes.color(name) : 0xFF888888);
            drawStr(graphics, prefix + box + name, x, y, col);
            y += rowH;
        }
        // Scroll indicator.
        drawStr(graphics, "  [" + (sel + 1) + "/" + vis.size() + "]", x, y, 0xFF777777);
    }

    private static void drawSettingsMenu(Object graphics) {
        barry.config.KeybindRegistry.Action[] acts = barry.ui.SettingsMenu.ACTIONS;
        int x = 140, top = 24, w = 260, rowH = 10;
        int h = (acts.length + 2) * rowH + 8;
        fillRect(graphics, x - 6, top - 6, x + w, top + h, 0xC0101014);
        fillRect(graphics, x - 6, top - 6, x + w, top - 5, 0xFF55DDFF);
        fillRect(graphics, x - 6, top + h - 1, x + w, top + h, 0xFF55DDFF);

        int y = top;
        drawStr(graphics, "KEYS  Esc:back  UP/DOWN:move  RIGHT:rebind", x, y, 0xFF55DDFF);
        y += rowH;
        int sel = barry.ui.SettingsMenu.selected();
        boolean capturing = barry.ui.SettingsMenu.isCapturing();
        for (int i = 0; i < acts.length; i++) {
            barry.config.KeybindRegistry.Action a = acts[i];
            boolean selected = (i == sel);
            if (selected) fillRect(graphics, x - 4, y - 1, x + w - 2, y + rowH - 2, 0x40FFFFFF);
            String keyLabel = (selected && capturing) ? "< press a key...  Esc=cancel >"
                                                      : Overlay.keyLabel(a);
            String prefix = selected ? "> " : "  ";
            int col = selected ? 0xFFFFFFFF : 0xFFBBBBBB;
            drawStr(graphics, prefix + a.description() + " : " + keyLabel, x, y, col);
            y += rowH;
        }
    }

    /** Draw a large centered countdown message during the RTP phase. */
    private static void drawCountdownMessage(Object graphics) {
        String text = barry.ui.NetheriteAutoMode.countdownText();
        if (text.isEmpty()) return;

        // Center on screen: assume 854x480 typical Minecraft resolution
        int centerX = 427;
        int centerY = 200;

        // Background bar — semi-transparent black
        int textWidth = text.length() * 9;
        fillRect(graphics, centerX - textWidth / 2 - 20, centerY - 14,
                centerX + textWidth / 2 + 20, centerY + 18, 0x80000000);

        // Text — white with nether amber tint
        drawStr(graphics, text, centerX - textWidth / 2, centerY, 0xFFFFFFFF);
    }

    // === Nether Auto panel ===

    private static void drawNetherMenu(Object graphics) {
        String[] items = barry.ui.Menu.NetherOptions.ITEMS;
        int sel = barry.ui.Menu.netherSel();
        int x = 140, top = 24, w = 260, rowH = 10;
        int rows = items.length;
        int h = (rows + 2) * rowH + 8;

        // Background with orange/amber theme for nether
        fillRect(graphics, x - 6, top - 6, x + w, top + h, 0xC01A1008);
        fillRect(graphics, x - 6, top - 6, x + w, top - 5, 0xFFFF8833);
        fillRect(graphics, x - 6, top + h - 1, x + w, top + h, 0xFFFF8833);

        int y = top;
        drawStr(graphics, "NETHER AUTO  Esc:back  UP/DOWN:move  RIGHT:toggle", x, y, 0xFFFF8833);
        y += rowH;

        for (int i = 0; i < items.length; i++) {
            boolean selected = (i == sel);
            if (selected) fillRect(graphics, x - 4, y - 1, x + w - 2, y + rowH - 2, 0x40FFFFFF);

            String label = items[i];
            int col;

            // Section headers (--- ...)
            if (label.startsWith("---")) {
                col = 0xFFCCAA66;
                drawStr(graphics, "    " + label, x, y, col);
                y += rowH;
                continue;
            }

            // Show current value for toggle options
            boolean on = false;
            switch (i) {
                case 0: on = barry.ui.NetheriteAutoMode.isEnabled(); break;
                case 2: on = barry.ui.NetheriteAutoMode.isAutoRtp(); break;
                case 3: on = barry.ui.NetheriteAutoMode.isStayInArea(); break;
                case 4: on = barry.ui.NetheriteAutoMode.isKeepSearching(); break;
                case 5: on = barry.ui.NetheriteAutoMode.isBrightDebrisEsp(); break;
                case 7: on = barry.ui.NetheriteAutoMode.isDisconnectOnFind(); break;
                case 8: on = barry.ui.NetheriteAutoMode.isHealthCheckDisconnect(); break;
            }

            if (i == 9) {
                // Health threshold — show the number
                int val = barry.ui.NetheriteAutoMode.getHealthThreshold();
                label = "Health Threshold: " + val;
                col = selected ? 0xFFFFFFFF : 0xFFCCCCCC;
            } else {
                String prefix = selected ? "> " : "  ";
                String box = on ? "[x] " : "[ ] ";
                if (on && !selected) col = 0xFFFFAA55; // warm amber when ON
                else if (on && selected) col = 0xFFFFFFFF;
                else col = selected ? 0xFFFFFFFF : 0xFF888888;
                label = prefix + box + label;
            }

            drawStr(graphics, label, x, y, col);
            y += rowH;
        }

        // Show current state at bottom
        String stateLine = barry.ui.NetheriteAutoMode.hudLine();
        if (!stateLine.isEmpty()) {
            y += 2;
            drawStr(graphics, "  " + stateLine, x, y, 0xFFFFCC66);
        }
    }

    private static volatile String[] cachedLines = {"[barry] ACTIVE"};
    private static volatile long linesStamp;

    private static String[] hudLines() {
        long now = System.currentTimeMillis();
        String[] cur = cachedLines;
        if (now - linesStamp < 200) return cur;
        linesStamp = now;
        try {
            XrayEngine eng = engine;
            if (eng == null) { cachedLines = new String[]{"[barry] ACTIVE"}; return cachedLines; }
            String dim = eng.currentDimension().name();
            var map = eng.currentMap();
            int ores = map.oreCount();
            var player = eng.playerPos();
            var near = map.oresNear(player, 64.0, 0.0f);

            java.util.List<String> out = new java.util.ArrayList<>();
            out.add("[barry] " + dim + " | ores:" + ores + " | BACKTICK=toggle DELETE=panic");

            // Nether Auto status line
            String netherLine = barry.ui.NetheriteAutoMode.hudLine();
            if (!netherLine.isEmpty()) {
                out.add("  " + netherLine);
            }

            // Active-scan status line: "scanning for ores + mobs" / "idle".
            int enBlocks = barry.core.OreType.enabledCount();
            int enEnts = barry.esp.EntityTypes.enabledCount();
            java.util.List<String> active = new java.util.ArrayList<>();
            if (enBlocks > 0) active.add("ores(" + enBlocks + ")");
            if (enEnts > 0) active.add("mobs(" + enEnts + ")");
            out.add(active.isEmpty() ? "scanning: idle (enable in F6 menu)"
                                     : "scanning for " + String.join(" + ", active));
            int shown = 0;
            for (var e : near) {
                if (shown >= 6) break;
                var ot = barry.core.OreType.fromBlockName(e.getValue().blockName());
                if (ot != null && !ot.isEnabled()) continue; // hidden via filter menu
                var p = e.getKey();
                int d = (int) Math.sqrt(p.distanceSqTo(player));
                out.add(String.format("  %-18s %d,%d,%d  (%dm)",
                        e.getValue().blockName(), p.x(), p.y(), p.z(), d));
                shown++;
            }
            if (shown == 0) out.add("  (no ores in range — scanning...)");
            cachedLines = out.toArray(new String[0]);
        } catch (Throwable t) {
            cachedLines = new String[]{"[barry] ACTIVE"};
        }
        return cachedLines;
    }

    // GuiGraphics.drawString(Font,String,int,int,int[,boolean]) [1.21.x] or
    // GuiGraphicsExtractor.text(Font,String,int,int,int[,boolean]) [26.x].
    private static Method resolveDrawMethod(Object graphics) {
        Method best = null;
        for (Method m : graphics.getClass().getMethods()) {
            String n = m.getName();
            if (!"drawString".equals(n) && !"text".equals(n)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length >= 5 && p[0].getSimpleName().equals("Font")
                    && p[1] == String.class
                    && p[2] == int.class && p[3] == int.class && p[4] == int.class
                    && (p.length == 5 || (p.length == 6 && p[5] == boolean.class))) {
                best = m;
                if (p.length == 6) break;
            }
        }
        return best;
    }

    // GuiGraphics.fill(int,int,int,int,int) [1.21.x] or
    // GuiGraphicsExtractor.fill(int,int,int,int,int) [26.x] — (x1,y1,x2,y2,argb).
    private static Method resolveFillMethod(Object graphics) {
        for (Method m : graphics.getClass().getMethods()) {
            if (!"fill".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 5 && p[0] == int.class && p[1] == int.class
                    && p[2] == int.class && p[3] == int.class && p[4] == int.class) {
                return m;
            }
        }
        return null;
    }

    private static Object resolveFont() {
        try {
            Object mc = minecraftInstance();
            if (mc == null) return null;
            for (Field f : mc.getClass().getDeclaredFields()) {
                if (f.getType().getSimpleName().equals("Font")) {
                    f.setAccessible(true);
                    return f.get(mc);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void installTransformer(Instrumentation inst) {
        try {
            inst.addTransformer(new GuiTransformer(), true);

            ClassLoader cl = barry.Bootstrap.findMinecraftClassLoader();
            if (cl == null) {
                System.err.println("[barry] RenderHook: no game classloader for retransform");
                return;
            }

            // 1.21.x renders the HUD in Gui.render(GuiGraphics,...); 26.x moved it
            // to Hud.extract*(GuiGraphicsExtractor,...). World outlines hook
            // LevelRenderer.submitBlockOutline. Retransform whichever exist.
            int hooked = 0;
            for (String target : new String[]{
                    "net.minecraft.client.gui.Hud",
                    "net.minecraft.client.gui.Gui",
                    "net.minecraft.client.renderer.LevelRenderer",
                    "net.minecraft.client.KeyboardHandler"}) {
                try {
                    inst.retransformClasses(cl.loadClass(target));
                    hooked++;
                } catch (Throwable ignored) {}
            }
            System.err.println("[barry] RenderHook: render hooks installed (" + hooked + " target class(es))");
        } catch (Throwable t) {
            System.err.println("[barry] RenderHook: transformer install failed: " + t.getMessage());
        }
    }
}
