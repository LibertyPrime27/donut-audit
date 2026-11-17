package barry.attach;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class GuiTransformer implements ClassFileTransformer {

    private static final java.util.Set<String> TARGET_CLASSES = java.util.Set.of(
            "net/minecraft/client/gui/Hud",            // 26.x: extract*(GuiGraphicsExtractor,...)
            "net/minecraft/client/gui/Gui",            // 1.21.x: render(GuiGraphics,...)
            "net/minecraft/client/renderer/LevelRenderer", // 26.x: submitBlockOutline(...)
            "net/minecraft/client/KeyboardHandler");   // consume input while a menu is open

    private static final int MAX_SUPPORTED_VERSION = 67; // ASM 9.7 ceiling (Java 23)

    @Override
    public byte[] transform(ClassLoader loader, String className,
            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {

        if (className == null || !TARGET_CLASSES.contains(className)) return null;
        final String shortName = className.substring(className.lastIndexOf('/') + 1);

        try {
            byte[] buf = classfileBuffer.clone();
            int originalMajor = ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);

            if (originalMajor > MAX_SUPPORTED_VERSION) {
                buf[6] = (byte) (MAX_SUPPORTED_VERSION >>> 8);
                buf[7] = (byte) (MAX_SUPPORTED_VERSION);
            }

            int[] hookCount = {0};
            ClassReader cr = new ClassReader(buf);
            // The input-capture hook inserts a conditional early-return (a branch),
            // which needs recomputed stack-map frames or the retransform fails
            // verification. Use COMPUTE_FRAMES there, with a loader-aware
            // getCommonSuperClass so frame computation can resolve game types.
            // Other targets only insert a call (no branch) → COMPUTE_MAXS is fine.
            boolean needFrames = "net/minecraft/client/KeyboardHandler".equals(className);
            ClassWriter cw = needFrames
                    ? new FrameClassWriter(cr, ClassWriter.COMPUTE_FRAMES, loader)
                    : new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                    // 26.x: Gui.extractRenderState calls Hud.extract*(GuiGraphicsExtractor,...)
                    // directly — hook each and record through the extractor (arg 1).
                    // 1.21.x: the HUD renders in Gui.render(GuiGraphics,...) — hook it (arg 1).
                    boolean hookExtract = name.startsWith("extract")
                            && descriptor.startsWith("(Lnet/minecraft/client/gui/GuiGraphicsExtractor;");
                    boolean hookGuiRender = "render".equals(name)
                            && descriptor.startsWith("(Lnet/minecraft/client/gui/GuiGraphics;");

                    // 26.x world hook: submitEntities(PoseStack, LevelRenderState, SubmitNodeCollector)
                    // runs every frame unconditionally. PoseStack=arg1, collector=arg3.
                    boolean hookWorldOutline = "submitEntities".equals(name)
                            && descriptor.startsWith("(Lcom/mojang/blaze3d/vertex/PoseStack;");
                    if (hookWorldOutline) {
                        hookCount[0]++;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                mv.visitVarInsn(Opcodes.ALOAD, 1); // PoseStack
                                mv.visitVarInsn(Opcodes.ALOAD, 3); // SubmitNodeCollector
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "barry/render/WorldRenderHook", "onWorldOutline",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                            }
                        };
                    }

                    // Capture the game's line RenderType from submitHitOutline(pose, collector, RenderType, ...).
                    boolean hookHitOutline = "submitHitOutline".equals(name)
                            && descriptor.startsWith("(Lcom/mojang/blaze3d/vertex/PoseStack;");
                    if (hookHitOutline) {
                        hookCount[0]++;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                mv.visitVarInsn(Opcodes.ALOAD, 3); // RenderType (arg index 2 -> slot 3)
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "barry/render/WorldRenderHook", "captureLineRenderType",
                                        "(Ljava/lang/Object;)V", false);
                            }
                        };
                    }

                    // Input capture: KeyboardHandler.keyPress/charTyped/textInput are
                    // all void. Inject "if (InputCapture.shouldConsume()) return;" at
                    // entry so keys don't reach the game while a menu is open.
                    boolean hookConsume = ("keyPress".equals(name) || "charTyped".equals(name)
                            || "textInput".equals(name));
                    if (hookConsume) {
                        hookCount[0]++;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "barry/ui/InputCapture", "shouldConsume", "()Z", false);
                                Label pass = new Label();
                                mv.visitJumpInsn(Opcodes.IFEQ, pass);
                                mv.visitInsn(Opcodes.RETURN);
                                mv.visitLabel(pass);
                            }
                        };
                    }

                    if (hookExtract || hookGuiRender) {
                        hookCount[0]++;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                mv.visitVarInsn(Opcodes.ALOAD, 1);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "barry/render/RenderHook",
                                        "onGuiRender",
                                        "(Ljava/lang/Object;)V",
                                        false);
                            }
                        };
                    }
                    return mv;
                }
            };
            cr.accept(cv, 0);
            System.err.println("[barry] " + shortName + " transform: " + hookCount[0] + " render method(s) hooked.");

            byte[] result = cw.toByteArray();
            if (originalMajor > MAX_SUPPORTED_VERSION) {
                result[6] = (byte) (originalMajor >>> 8);
                result[7] = (byte) (originalMajor);
            }

            return result;
        } catch (Throwable t) {
            System.err.println("[barry] Failed to transform " + shortName + ": " + t.getMessage());
            return null;
        }
    }

    // ClassWriter whose getCommonSuperClass resolves types through the class's own
    // (game) classloader and falls back to Object, so COMPUTE_FRAMES works inside
    // the agent without the game classes on the agent classpath.
    private static final class FrameClassWriter extends ClassWriter {
        private final ClassLoader loader;
        FrameClassWriter(ClassReader cr, int flags, ClassLoader loader) {
            super(cr, flags);
            this.loader = loader != null ? loader : getClass().getClassLoader();
        }
        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                Class<?> c1 = Class.forName(type1.replace('/', '.'), false, loader);
                Class<?> c2 = Class.forName(type2.replace('/', '.'), false, loader);
                if (c1.isAssignableFrom(c2)) return type1;
                if (c2.isAssignableFrom(c1)) return type2;
                if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
                do { c1 = c1.getSuperclass(); } while (c1 != null && !c1.isAssignableFrom(c2));
                return c1 == null ? "java/lang/Object" : c1.getName().replace('.', '/');
            } catch (Throwable e) {
                return "java/lang/Object";
            }
        }
    }
}
