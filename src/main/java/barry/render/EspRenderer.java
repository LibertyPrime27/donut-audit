package barry.render;

import barry.core.BlockConfidence;
import barry.core.BlockMap;
import barry.core.OreType;
import barry.util.BlockPos;
import barry.xray.XrayEngine;

import java.lang.reflect.Method;
import java.util.*;

public final class EspRenderer {

    private final XrayEngine engine;
    private volatile boolean enabled = true;
    private volatile ClassLoader cachedClassLoader;

    // Render settings
    private float confidenceFloor = 0.5f;
    private double renderRadius = 64.0;
    private double fadeStart = 48.0;
    private float maxOpacity = 0.8f;
    private boolean showTracers = true;
    private int maxTracers = 5;
    private boolean showHeatmap = false;
    private boolean showChunkGrid = false;
    private boolean showConfidence = true;

    // Per-ore-type visibility
    private final Set<OreType> visibleTypes = EnumSet.allOf(OreType.class);

    // Cached render list (rebuilt each frame from the block map)
    private volatile List<RenderEntry> renderList = List.of();

    public record RenderEntry(BlockPos pos, OreType type, float confidence,
                               double distance, int color, float alpha) {}

    public EspRenderer(XrayEngine engine) {
        this.engine = engine;
    }

    public void buildRenderList() {
        if (!enabled || engine == null) {
            renderList = List.of();
            return;
        }

        BlockMap map = engine.currentMap();
        BlockPos player = engine.playerPos();
        double r2 = renderRadius * renderRadius;

        Map<Long, BlockConfidence> ores = map.orePositions();
        List<RenderEntry> entries = new ArrayList<>(Math.min(ores.size(), 2000));

        for (Map.Entry<Long, BlockConfidence> e : ores.entrySet()) {
            BlockConfidence conf = e.getValue();
            if (conf.confidence() < confidenceFloor) continue;

            OreType type = OreType.fromBlockName(conf.blockName());
            if (type == null || !visibleTypes.contains(type)) continue;

            BlockPos pos = BlockPos.fromPacked(e.getKey());
            double dist = pos.distanceSqTo(player);
            if (dist > r2) continue;

            double linearDist = Math.sqrt(dist);
            float alpha = maxOpacity;
            if (linearDist > fadeStart) {
                alpha *= (float) (1.0 - (linearDist - fadeStart) / (renderRadius - fadeStart));
            }

            // Confidence shading: low confidence = more transparent
            alpha *= conf.confidence();

            int color = type.color();
            entries.add(new RenderEntry(pos, type, conf.confidence(), linearDist, color, alpha));
        }

        // Sort by distance for rendering order
        entries.sort(Comparator.comparingDouble(RenderEntry::distance));

        // Cap render list to avoid performance issues
        if (entries.size() > 2000) {
            entries = entries.subList(0, 2000);
        }

        renderList = entries;
    }

    public void render(Object poseStack, Object bufferSource, double camX, double camY, double camZ) {
        if (!enabled) return;

        List<RenderEntry> list = renderList;
        if (list.isEmpty()) return;

        // This method will be called from the render hook injected into
        // GameRenderer or LevelRenderer. The actual OpenGL/rendering calls
        // depend on the Minecraft version and rendering API.
        //
        // For modern MC (1.20+) with the Blaze3D rendering pipeline:
        // - Use RenderSystem for state management
        // - Use BufferBuilder/VertexConsumer for geometry
        // - Use PoseStack for matrix transforms
        //
        // The rendering is done via reflection to avoid compile-time
        // dependencies on Minecraft classes.

        try {
            renderBoxes(poseStack, bufferSource, camX, camY, camZ, list);
            if (showTracers) {
                renderTracers(poseStack, bufferSource, camX, camY, camZ, list);
            }
        } catch (Throwable t) {
            // Fail silent — rendering errors must not crash the game
        }
    }

    private void renderBoxes(Object poseStack, Object bufferSource,
                              double camX, double camY, double camZ,
                              List<RenderEntry> entries) throws Exception {
        if (cachedClassLoader == null) cachedClassLoader = barry.Bootstrap.findMinecraftClassLoader();
        ClassLoader cl = cachedClassLoader;
        if (cl == null) return;

        // Get RenderSystem class for GL state
        Class<?> renderSystem = cl.loadClass(
            barry.attach.Remap.resolveClass("com.mojang.blaze3d.systems.RenderSystem"));

        // Enable blending for transparency
        Method enableBlend = renderSystem.getMethod("enableBlend");
        Method defaultBlendFunc = renderSystem.getMethod("defaultBlendFunc");
        Method disableDepthTest = renderSystem.getMethod("disableDepthTest");
        Method enableDepthTest = renderSystem.getMethod("enableDepthTest");
        Method disableBlend = renderSystem.getMethod("disableBlend");
        Method setShaderColor = renderSystem.getMethod("setShaderColor", float.class, float.class, float.class, float.class);

        enableBlend.invoke(null);
        defaultBlendFunc.invoke(null);
        disableDepthTest.invoke(null);

        for (RenderEntry entry : entries) {
            float r = ((entry.color >> 16) & 0xFF) / 255f;
            float g = ((entry.color >> 8) & 0xFF) / 255f;
            float b = (entry.color & 0xFF) / 255f;
            float a = entry.alpha;

            setShaderColor.invoke(null, r, g, b, a);

            // Draw a wireframe box at the block position
            double x = entry.pos.x() - camX;
            double y = entry.pos.y() - camY;
            double z = entry.pos.z() - camZ;

            renderWireBox(poseStack, cl, x, y, z, 1.0, 1.0, 1.0, r, g, b, a);
        }

        setShaderColor.invoke(null, 1f, 1f, 1f, 1f);
        enableDepthTest.invoke(null);
        disableBlend.invoke(null);
    }

    private void renderWireBox(Object poseStack, ClassLoader cl,
                                double x, double y, double z,
                                double w, double h, double d,
                                float r, float g, float b, float a) throws Exception {
        // Use LevelRenderer.renderLineBox or equivalent
        // This is version-dependent and resolved via reflection
        String lrClassName = barry.attach.Remap.resolveClass("net.minecraft.client.renderer.LevelRenderer");
        Class<?> lrClass = cl.loadClass(lrClassName);

        // Find renderLineBox static method
        for (Method m : lrClass.getDeclaredMethods()) {
            if (m.getParameterCount() >= 10 && m.getReturnType() == void.class) {
                Class<?>[] params = m.getParameterTypes();
                // Looking for renderLineBox(PoseStack, VertexConsumer, double*6, float*4)
                if (params.length == 13 && params[2] == double.class) {
                    // Found it
                    // We'd need a VertexConsumer from the buffer source
                    // For now, skip the actual GL call — this is the hook point
                    break;
                }
            }
        }

        // Fallback: direct GL line drawing
        // This works across versions but is deprecated in modern MC
    }

    private void renderTracers(Object poseStack, Object bufferSource,
                                double camX, double camY, double camZ,
                                List<RenderEntry> entries) {
        // Draw lines from the camera to the nearest N ore positions
        int count = Math.min(maxTracers, entries.size());
        for (int i = 0; i < count; i++) {
            RenderEntry entry = entries.get(i);
            // Line from (0, 0, 0) camera-relative to block center
            double tx = entry.pos.x() + 0.5 - camX;
            double ty = entry.pos.y() + 0.5 - camY;
            double tz = entry.pos.z() + 0.5 - camZ;
            // Actual line rendering via GL or VertexConsumer
        }
    }

    // --- Settings ---

    public void setConfidenceFloor(float floor) { this.confidenceFloor = Math.max(0, Math.min(1, floor)); }
    public void setRenderRadius(double radius) { this.renderRadius = radius; this.fadeStart = radius * 0.75; }
    public void setMaxOpacity(float opacity) { this.maxOpacity = Math.max(0, Math.min(1, opacity)); }
    public void setShowTracers(boolean show) { this.showTracers = show; }
    public void setMaxTracers(int max) { this.maxTracers = max; }
    public void setShowHeatmap(boolean show) { this.showHeatmap = show; }
    public void setShowChunkGrid(boolean show) { this.showChunkGrid = show; }
    public void setShowConfidence(boolean show) { this.showConfidence = show; }
    public void setOreTypeVisible(OreType type, boolean visible) {
        if (visible) visibleTypes.add(type); else visibleTypes.remove(type);
    }
    public void setEnabled(boolean e) { this.enabled = e; }
    public boolean isEnabled() { return enabled; }

    public float confidenceFloor() { return confidenceFloor; }
    public double renderRadius() { return renderRadius; }
    public int visibleOreCount() { return renderList.size(); }
    public List<RenderEntry> renderList() { return renderList; }
}
