package barry.render;

import barry.attach.AgentLoader;
import barry.attach.Remap;
import barry.ui.Overlay;
import barry.xray.XrayEngine;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
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
        renderThread = new Thread(RenderHook::renderLoop, "barry-render-sync");
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

    private static void installTransformer(Instrumentation inst) {
        // The transformer injects a callback at the end of:
        // 1. LevelRenderer.renderLevel() — for world ESP rendering
        // 2. Gui.render() — for HUD rendering
        //
        // The injection point is a static method call to RenderHook.onRenderWorld()
        // or RenderHook.onRenderHud() inserted via ASM-free bytecode patching.
        //
        // For M1, we use the polling render thread instead, which doesn't require
        // bytecode modification. The transformer is a M3+ enhancement.
        System.err.println("[barry] RenderHook: transformer install deferred to M3");
    }
}
