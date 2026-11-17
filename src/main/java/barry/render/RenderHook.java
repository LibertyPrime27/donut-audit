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

    private static Object findFieldByType(Object obj, String typeNameContains) {
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
                if (drawShadow) cachedDraw.invoke(graphics, cachedFont, lines[i], 4, y, color, true);
                else cachedDraw.invoke(graphics, cachedFont, lines[i], 4, y, color);
                y += 10;
            }
        } catch (Throwable ignored) {}
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
            int shown = 0;
            for (var e : near) {
                if (shown >= 6) break;
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
                    "net.minecraft.client.renderer.LevelRenderer"}) {
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
