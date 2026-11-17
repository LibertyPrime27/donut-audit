package barry.render;

import barry.core.OreType;
import barry.ui.Overlay;
import barry.util.BlockPos;
import barry.xray.XrayEngine;

import java.lang.reflect.Method;

// Draws 3D ore outlines in the world using 26.3's retained-mode submit pipeline.
// GuiTransformer injects a call to onWorldOutline at the head of
// LevelRenderer.submitBlockOutline(PoseStack, SubmitNodeCollector, LevelRenderState),
// which runs during world rendering and hands us a PoseStack (camera-relative)
// and a SubmitNodeCollector. For each nearby ore we translate the pose to the
// block and submit a full-cube VoxelShape outline via
// SubmitNodeCollector.submitShapeOutline(pose, shape, lines, color, width, depthTest).
public final class WorldRenderHook {

    private static volatile XrayEngine engine;
    private static volatile boolean ready;
    private static volatile boolean resolveFailed;

    // Cached handles
    private static Object blockShape;        // Shapes.block() -> VoxelShape
    private static volatile Object linesRenderType;   // RenderType for line outlines
    private static volatile Object capturedLineType;  // grabbed from submitHitOutline
    private static Method mSubmitShapeOutline;
    private static Method mPush, mTranslate, mPop;
    private static Object camera;            // Camera (re-fetched each frame)
    private static Method mMainCamera, mPosition;
    private static java.lang.reflect.Field vecX, vecY, vecZ;
    private static Object gameRenderer;

    private WorldRenderHook() {}

    public static void init(XrayEngine eng) { engine = eng; }
    public static void shutdown() { engine = null; ready = false; resolveFailed = false; capturedLineType = null; }

    // Injected at submitHitOutline — captures the game's own line RenderType so
    // we can reuse it (no static factory for it on 26.x).
    public static void captureLineRenderType(Object renderType) {
        if (capturedLineType == null && renderType != null) {
            capturedLineType = renderType;
            System.err.println("[barry] captured line RenderType: " + renderType.getClass().getName());
        }
    }

    public static void onWorldOutline(Object pose, Object collector) {
        XrayEngine eng = engine;
        if (eng == null || !Overlay.isVisible()) return;
        if (resolveFailed) return;
        try {
            if (!ready && !resolve(pose, collector)) { resolveFailed = true; return; }

            // Resolve the line RenderType lazily (static factory, else captured).
            if (linesRenderType == null) {
                linesRenderType = (capturedLineType != null) ? capturedLineType : null;
                if (linesRenderType == null) return; // wait until we capture one
                System.err.println("[barry] world outlines active");
            }

            // Camera position (pose is camera-relative).
            Object cam = mMainCamera.invoke(gameRenderer);
            Object pv = mPosition.invoke(cam);
            double camX = vecX.getDouble(pv), camY = vecY.getDouble(pv), camZ = vecZ.getDouble(pv);

            BlockPos player = eng.playerPos();
            var near = eng.currentMap().oresNear(player, 64.0, 0.0f);
            for (var e : near) {
                BlockPos p = e.getKey();
                OreType ore = OreType.fromBlockName(e.getValue().blockName());
                int color = ore != null ? ore.color() : 0xFFFFFFFF;
                mPush.invoke(pose);
                mTranslate.invoke(pose, p.x() - camX, p.y() - camY, p.z() - camZ);
                // (pose, shape, renderType, colorARGB, width/alpha, depthTest)
                mSubmitShapeOutline.invoke(collector, pose, blockShape, linesRenderType, color, 1.0f, false);
                mPop.invoke(pose);
            }
        } catch (Throwable t) {
            // Never crash the render thread.
            if (!resolveFailed) {
                System.err.println("[barry] world outline error: " + t);
                resolveFailed = true;
            }
        }
    }

    private static boolean resolve(Object pose, Object collector) {
        try {
            ClassLoader cl = RenderHook.minecraftInstance().getClass().getClassLoader();

            // Shapes.block() -> VoxelShape
            Class<?> shapes = cl.loadClass("net.minecraft.world.phys.shapes.Shapes");
            blockShape = shapes.getMethod("block").invoke(null);

            // Try a static lines() factory; if absent we capture one from the
            // game's hit-outline render instead (set lazily in onWorldOutline).
            try {
                Class<?> rtClass = cl.loadClass("net.minecraft.client.renderer.rendertype.RenderType");
                linesRenderType = findLinesRenderType(rtClass);
            } catch (Throwable ignored) {}

            // PoseStack push/translate/pop
            Class<?> poseClass = pose.getClass();
            mPush = poseClass.getMethod("pushPose");
            mPop = poseClass.getMethod("popPose");
            mTranslate = poseClass.getMethod("translate", double.class, double.class, double.class);

            // submitShapeOutline(PoseStack, VoxelShape, RenderType, int, float, boolean)
            for (Method m : collector.getClass().getMethods()) {
                if (m.getName().equals("submitShapeOutline") && m.getParameterCount() == 6) {
                    mSubmitShapeOutline = m; break;
                }
            }
            if (mSubmitShapeOutline == null) { System.err.println("[barry] no submitShapeOutline"); return false; }

            // Camera handles
            Object mc = RenderHook.minecraftInstance();
            gameRenderer = fieldByTypeName(mc, "GameRenderer");
            for (Method m : gameRenderer.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().equals("Camera")) { mMainCamera = m; break; }
            }
            Object cam = mMainCamera.invoke(gameRenderer);
            for (Method m : cam.getClass().getMethods()) {
                if (m.getName().equals("position") && m.getParameterCount() == 0) { mPosition = m; break; }
            }
            Object pv = mPosition.invoke(cam);
            vecX = pv.getClass().getField("x");
            vecY = pv.getClass().getField("y");
            vecZ = pv.getClass().getField("z");

            ready = true;
            System.err.println("[barry] WorldRenderHook ready (3D outlines via submitShapeOutline)");
            return true;
        } catch (Throwable t) {
            System.err.println("[barry] WorldRenderHook resolve failed: " + t);
            return false;
        }
    }

    private static Object findLinesRenderType(Class<?> rtClass) {
        try {
            for (Method m : rtClass.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 0
                        && m.getName().equals("lines")
                        && rtClass.isAssignableFrom(m.getReturnType())) {
                    return m.invoke(null);
                }
            }
            // fallback: any static no-arg returning RenderType with "line" in the name
            for (Method m : rtClass.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 0
                        && m.getName().toLowerCase().contains("line")
                        && rtClass.isAssignableFrom(m.getReturnType())) {
                    return m.invoke(null);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object fieldByTypeName(Object obj, String typeContains) {
        for (var f : obj.getClass().getDeclaredFields()) {
            if (f.getType().getSimpleName().contains(typeContains)) {
                try { f.setAccessible(true); Object v = f.get(obj); if (v != null) return v; }
                catch (Throwable ignored) {}
            }
        }
        return null;
    }
}
