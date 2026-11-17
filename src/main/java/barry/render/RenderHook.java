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

        INSTALLED.set(true);
        System.err.println("[barry] RenderHook installed.");
    }

    public static void shutdown() {
        INSTALLED.set(false);
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

    private static volatile Object cachedFont;
    private static volatile Method cachedText;
    private static volatile boolean drawShadow = true;

    // Injected at the head of each Hud.extract*(GuiGraphicsExtractor, ...) call.
    // We draw in every one: each extract method runs with its own coordinate
    // transform on the extractor, and only some are screen-space. Drawing in all
    // of them guarantees a stable, non-flickering overlay (duplicate records at
    // the same position just overdraw).
    public static void onGuiRender(Object extractor) {
        if (!INSTALLED.get() || !Overlay.isVisible()) return;
        try {
            if (cachedFont == null) cachedFont = resolveFont();
            if (cachedFont == null) return;

            if (cachedText == null) {
                cachedText = resolveTextMethod(extractor);
                if (cachedText == null) return;
                drawShadow = cachedText.getParameterCount() == 6;
            }

            String dim = engine != null ? engine.currentDimension().name() : "?";
            long ores = engine != null ? engine.currentMap().oreCount() : 0;
            long gt = engine != null ? engine.currentMap().groundTruthCount() : 0;

            String line1 = "[barry] ACTIVE | " + dim + " | ores:" + ores + " gt:" + gt;
            String line2 = "BACKTICK=toggle | DELETE=panic";
            if (drawShadow) {
                cachedText.invoke(extractor, cachedFont, line1, 4, 4, 0xFF55FF55, true);
                cachedText.invoke(extractor, cachedFont, line2, 4, 16, 0xFFAAAAAA, true);
            } else {
                cachedText.invoke(extractor, cachedFont, line1, 4, 4, 0xFF55FF55);
                cachedText.invoke(extractor, cachedFont, line2, 4, 16, 0xFFAAAAAA);
            }
        } catch (Throwable ignored) {}
    }

    // GuiGraphicsExtractor.text(Font, String, int, int, int[, boolean])
    private static Method resolveTextMethod(Object extractor) {
        Method best = null;
        for (Method m : extractor.getClass().getMethods()) {
            if (!"text".equals(m.getName())) continue;
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

            // MC 26.3 split the HUD out of Gui into net.minecraft.client.gui.Hud.
            Class<?> hud = cl.loadClass("net.minecraft.client.gui.Hud");
            inst.retransformClasses(hud);
            System.err.println("[barry] RenderHook: HUD render hook installed via ASM");
        } catch (Throwable t) {
            System.err.println("[barry] RenderHook: transformer install failed: " + t.getMessage());
        }
    }
}
