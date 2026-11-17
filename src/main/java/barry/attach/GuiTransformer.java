package barry.attach;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class GuiTransformer implements ClassFileTransformer {

    private static final java.util.Set<String> TARGET_CLASSES = java.util.Set.of(
            "net/minecraft/client/gui/Hud",   // 26.x: extract*(GuiGraphicsExtractor,...)
            "net/minecraft/client/gui/Gui");  // 1.21.x: render(GuiGraphics,...)

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
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
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
}
