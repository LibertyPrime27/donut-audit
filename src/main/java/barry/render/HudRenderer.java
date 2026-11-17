package barry.render;

import barry.core.BlockMap;
import barry.core.OreType;
import barry.util.Dimension;
import barry.xray.XrayEngine;

public final class HudRenderer {

    private final XrayEngine engine;
    private volatile boolean showStats = true;
    private volatile boolean showDebris = true;
    private volatile boolean showDiamonds = true;

    public HudRenderer(XrayEngine engine) {
        this.engine = engine;
    }

    public void render(Object guiGraphics, float partialTick) {
        if (engine == null) return;

        // Build HUD text lines
        StringBuilder sb = new StringBuilder();

        Dimension dim = engine.currentDimension();
        BlockMap map = engine.currentMap();

        sb.append("[barry] ").append(dim.name());

        if (showStats) {
            sb.append(" | ores:").append(map.oreCount());
            sb.append(" gt:").append(map.groundTruthCount());
            sb.append(" | ").append(engine.statsLine());
        }

        // The actual text rendering requires Minecraft's Font class
        // which we access via reflection. The rendering path is:
        //   guiGraphics.drawString(font, text, x, y, color)
        // or in older versions:
        //   fontRenderer.drawString(text, x, y, color)

        try {
            renderText(guiGraphics, sb.toString(), 4, 4, 0xFFFFFFFF);

            // Render nearest ore info if visible
            EspRenderer esp = RenderHook.esp();
            if (esp != null && !esp.renderList().isEmpty()) {
                var nearest = esp.renderList().getFirst();
                String info = String.format("Nearest: %s @ %.0f blocks (%.0f%% conf)",
                    nearest.type().blockName(),
                    nearest.distance(),
                    nearest.confidence() * 100);
                renderText(guiGraphics, info, 4, 16, nearest.color());
            }
        } catch (Throwable ignored) {}
    }

    private void renderText(Object guiGraphics, String text, int x, int y, int color) throws Exception {
        ClassLoader cl = barry.Bootstrap.findMinecraftClassLoader();
        if (cl == null) return;

        if (guiGraphics == null) return;

        // GuiGraphics.drawString(Font, String, int, int, int)
        // We need the Font from Minecraft.font
        String mcClassName = barry.attach.Remap.resolveClass("net.minecraft.client.Minecraft");
        Class<?> mcClass = cl.loadClass(mcClassName);

        Object mc = null;
        for (java.lang.reflect.Method m : mcClass.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == mcClass
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                mc = m.invoke(null);
                break;
            }
        }
        if (mc == null) return;

        // Find font field
        Object font = null;
        for (java.lang.reflect.Field f : mc.getClass().getDeclaredFields()) {
            if (f.getType().getSimpleName().contains("Font")) {
                f.setAccessible(true);
                font = f.get(mc);
                break;
            }
        }
        if (font == null) return;

        // Call drawString
        for (java.lang.reflect.Method m : guiGraphics.getClass().getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 5 && params[0] == font.getClass()
                    && params[1] == String.class
                    && params[2] == int.class && params[3] == int.class
                    && params[4] == int.class) {
                m.setAccessible(true);
                m.invoke(guiGraphics, font, text, x, y, color);
                return;
            }
        }
    }

    public void setShowStats(boolean show) { this.showStats = show; }
    public void setShowDebris(boolean show) { this.showDebris = show; }
    public void setShowDiamonds(boolean show) { this.showDiamonds = show; }
}
