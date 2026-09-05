package com.reverse.agent;

import org.objectweb.asm.*;

abstract class ReplacingMethodVisitor extends MethodVisitor {
    ReplacingMethodVisitor(MethodVisitor methodVisitor) {
        super(Opcodes.ASM9, methodVisitor);
    }

    @Override
    public final void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
    }

    @Override
    public final void visitLabel(Label label) {
    }

    @Override
    public final void visitLineNumber(int line, Label start) {
    }

    @Override
    public final void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
    }

    @Override
    public final void visitLocalVariable(String name, String descriptor, String signature,
                                         Label start, Label end, int index) {
    }

    @Override
    public final void visitInsn(int opcode) {
    }

    @Override
    public final void visitIntInsn(int opcode, int operand) {
    }

    @Override
    public final void visitVarInsn(int opcode, int varIndex) {
    }

    @Override
    public final void visitTypeInsn(int opcode, String type) {
    }

    @Override
    public final void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
    }

    @Override
    public final void visitMethodInsn(int opcode, String owner, String name,
                                      String descriptor, boolean isInterface) {
    }

    @Override
    public final void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethod,
                                             Object... bootstrapMethodArguments) {
    }

    @Override
    public final void visitJumpInsn(int opcode, Label label) {
    }

    @Override
    public final void visitLdcInsn(Object value) {
    }

    @Override
    public final void visitIincInsn(int varIndex, int increment) {
    }

    @Override
    public final void visitTableSwitchInsn(int min, int max, Label defaultHandler, Label... labels) {
    }

    @Override
    public final void visitLookupSwitchInsn(Label defaultHandler, int[] keys, Label[] labels) {
    }

    @Override
    public final void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
    }

    @Override
    public final AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath,
                                                       String descriptor, boolean visible) {
        return null;
    }

    @Override
    public final AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath,
                                                           String descriptor, boolean visible) {
        return null;
    }

    @Override
    public final AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath,
                                                                Label[] start, Label[] end, int[] indexes,
                                                                String descriptor, boolean visible) {
        return null;
    }

    @Override
    public final void visitAttribute(Attribute attribute) {
    }
}

final class NeutralizeVisitor extends ReplacingMethodVisitor {
    NeutralizeVisitor(MethodVisitor methodVisitor) {
        super(methodVisitor);
    }

    @Override
    public void visitCode() {
        super.visitInsn(Opcodes.RETURN);
    }
}

final class ForceTrueVisitor extends ReplacingMethodVisitor {
    ForceTrueVisitor(MethodVisitor methodVisitor) {
        super(methodVisitor);
    }

    @Override
    public void visitCode() {
        super.visitInsn(Opcodes.ICONST_1);
        super.visitInsn(Opcodes.IRETURN);
    }
}

final class ReturnNullVisitor extends ReplacingMethodVisitor {
    ReturnNullVisitor(MethodVisitor methodVisitor) {
        super(methodVisitor);
    }

    @Override
    public void visitCode() {
        super.visitInsn(Opcodes.ACONST_NULL);
        super.visitInsn(Opcodes.ARETURN);
    }
}

final class PrimitiveReturnVisitor extends ReplacingMethodVisitor {
    private final char returnType;

    PrimitiveReturnVisitor(MethodVisitor methodVisitor, char returnType) {
        super(methodVisitor);
        this.returnType = returnType;
    }

    @Override
    public void visitCode() {
        switch (returnType) {
            case 'Z' -> super.visitInsn(Opcodes.ICONST_1);
            case 'J' -> super.visitInsn(Opcodes.LCONST_0);
            case 'F' -> super.visitInsn(Opcodes.FCONST_0);
            case 'D' -> super.visitInsn(Opcodes.DCONST_0);
            default -> super.visitInsn(Opcodes.ICONST_0);
        }
        switch (returnType) {
            case 'J' -> super.visitInsn(Opcodes.LRETURN);
            case 'F' -> super.visitInsn(Opcodes.FRETURN);
            case 'D' -> super.visitInsn(Opcodes.DRETURN);
            default -> super.visitInsn(Opcodes.IRETURN);
        }
    }
}

final class ReturnSuccessVisitor extends ReplacingMethodVisitor {
    private final String returnDescriptor;

    ReturnSuccessVisitor(MethodVisitor methodVisitor, String returnDescriptor) {
        super(methodVisitor);
        this.returnDescriptor = returnDescriptor;
    }

    @Override
    public void visitCode() {
        super.visitTypeInsn(Opcodes.NEW, "com/google/gson/Gson");
        super.visitInsn(Opcodes.DUP);
        super.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/google/gson/Gson",
                "<init>", "()V", false);
        super.visitLdcInsn("{\"success\":true}");
        super.visitLdcInsn(Type.getType(returnDescriptor));
        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/gson/Gson", "fromJson",
                "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false);
        super.visitTypeInsn(Opcodes.CHECKCAST, returnDescriptor);
        super.visitInsn(Opcodes.ARETURN);
    }
}

final class JsonDeserializeVisitor extends ReplacingMethodVisitor {
    private final String returnDescriptor;
    private final int argumentIndex;
    private final Label originalEntry = new Label();

    JsonDeserializeVisitor(MethodVisitor methodVisitor, String returnDescriptor, int argumentIndex) {
        super(methodVisitor);
        this.returnDescriptor = returnDescriptor;
        this.argumentIndex = argumentIndex;
    }

    @Override
    public void visitCode() {
        super.visitVarInsn(Opcodes.ALOAD, argumentIndex);
        super.visitJumpInsn(Opcodes.IFNULL, originalEntry);
        super.visitVarInsn(Opcodes.ALOAD, argumentIndex);
        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim",
                "()Ljava/lang/String;", false);
        super.visitInsn(Opcodes.DUP);
        super.visitLdcInsn("{");
        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith",
                "(Ljava/lang/String;)Z", false);
        Label parseObject = new Label();
        Label parseArray = new Label();
        Label parseJson = new Label();
        super.visitJumpInsn(Opcodes.IFNE, parseObject);
        super.visitLdcInsn("[");
        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith",
                "(Ljava/lang/String;)Z", false);
        super.visitJumpInsn(Opcodes.IFNE, parseArray);
        super.visitJumpInsn(Opcodes.GOTO, originalEntry);
        super.visitLabel(parseObject);
        super.visitInsn(Opcodes.POP);
        super.visitJumpInsn(Opcodes.GOTO, parseJson);
        super.visitLabel(parseArray);
        super.visitLabel(parseJson);
        super.visitTypeInsn(Opcodes.NEW, "com/google/gson/Gson");
        super.visitInsn(Opcodes.DUP);
        super.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/google/gson/Gson",
                "<init>", "()V", false);
        super.visitVarInsn(Opcodes.ALOAD, argumentIndex);
        super.visitLdcInsn(Type.getType(returnDescriptor));
        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/gson/Gson", "fromJson",
                "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false);
        super.visitTypeInsn(Opcodes.CHECKCAST, returnDescriptor);
        super.visitInsn(Opcodes.ARETURN);
        super.visitLabel(originalEntry);
    }
}
