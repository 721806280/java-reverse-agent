package com.reverse.agent;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * 方法体整体替换的基类。
 * <p>
 * 关键点：父类 {@link MethodVisitor} 的委托传入 {@code null}，因此
 * {@link org.objectweb.asm.ClassReader} 驱动的原方法体（各种 visitXxxInsn）
 * 全部被丢弃；替换指令由子类在 {@link #emit()} 里通过 {@link #target}
 * 直接写入真实的 writer，并由本类统一驱动 visitCode/visitMaxs/visitEnd。
 */
abstract class ReplacingMethodVisitor extends MethodVisitor {

    /**
     * 真实的下游 writer——替换指令写到这里。
     */
    final MethodVisitor target;

    ReplacingMethodVisitor(MethodVisitor target) {
        // 委托传 null：原方法体的所有回调都变成 no-op，被彻底压制。
        super(Opcodes.ASM9, null);
        this.target = target;
    }

    /**
     * 子类在此写入替换后的指令序列（不含 visitCode/visitMaxs/visitEnd）。
     */
    abstract void emit();

    @Override
    public final void visitCode() {
        target.visitCode();
        emit();
        // COMPUTE_MAXS / COMPUTE_FRAMES 会重算，这里传 0 即可。
        target.visitMaxs(0, 0);
        target.visitEnd();
    }
}

final class ForceTrueVisitor extends ReplacingMethodVisitor {
    ForceTrueVisitor(MethodVisitor methodVisitor) {
        super(methodVisitor);
    }

    @Override
    void emit() {
        target.visitInsn(Opcodes.ICONST_1);
        target.visitInsn(Opcodes.IRETURN);
    }
}

/**
 * 让 {@code ()Ljava/lang/Boolean;} getter 恒返回装箱的 {@link Boolean#TRUE}。
 */
final class ForceBoxedBooleanVisitor extends ReplacingMethodVisitor {
    ForceBoxedBooleanVisitor(MethodVisitor methodVisitor) {
        super(methodVisitor);
    }

    @Override
    void emit() {
        target.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
        target.visitInsn(Opcodes.ARETURN);
    }
}

/**
 * 让 {@code ()J} getter 恒返回远期时间戳常量。
 */
final class ForceLongVisitor extends ReplacingMethodVisitor {
    private final long value;

    ForceLongVisitor(MethodVisitor methodVisitor, long value) {
        super(methodVisitor);
        this.value = value;
    }

    @Override
    void emit() {
        target.visitLdcInsn(value);
        target.visitInsn(Opcodes.LRETURN);
    }
}

/**
 * 让 {@code ()Ljava/lang/Long;} getter 恒返回装箱的远期时间戳。
 */
final class ForceBoxedLongVisitor extends ReplacingMethodVisitor {
    private final long value;

    ForceBoxedLongVisitor(MethodVisitor methodVisitor, long value) {
        super(methodVisitor);
        this.value = value;
    }

    @Override
    void emit() {
        target.visitLdcInsn(value);
        target.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                "(J)Ljava/lang/Long;", false);
        target.visitInsn(Opcodes.ARETURN);
    }
}

/**
 * 把无参 {@code ()V} 方法整体清空为直接 {@code return}，从源头关停自成一体的过程。
 */
final class SilenceVoidVisitor extends ReplacingMethodVisitor {
    SilenceVoidVisitor(MethodVisitor methodVisitor) {
        super(methodVisitor);
    }

    @Override
    void emit() {
        target.visitInsn(Opcodes.RETURN);
    }
}

final class JsonDeserializeVisitor extends ReplacingMethodVisitor {
    private final String returnDescriptor;
    private final int argumentIndex;

    JsonDeserializeVisitor(MethodVisitor methodVisitor, String returnDescriptor, int argumentIndex) {
        super(methodVisitor);
        this.returnDescriptor = returnDescriptor;
        this.argumentIndex = argumentIndex;
    }

    @Override
    void emit() {
        // 参数为 null 时直接返回 null，否则用 Gson 反序列化为声明的返回类型。
        Label deserialize = new Label();
        target.visitVarInsn(Opcodes.ALOAD, argumentIndex);
        target.visitJumpInsn(Opcodes.IFNONNULL, deserialize);
        target.visitInsn(Opcodes.ACONST_NULL);
        target.visitInsn(Opcodes.ARETURN);

        target.visitLabel(deserialize);
        target.visitTypeInsn(Opcodes.NEW, "com/google/gson/Gson");
        target.visitInsn(Opcodes.DUP);
        target.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/google/gson/Gson",
                "<init>", "()V", false);
        target.visitVarInsn(Opcodes.ALOAD, argumentIndex);
        target.visitLdcInsn(Type.getType(returnDescriptor));
        target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/gson/Gson", "fromJson",
                "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false);
        target.visitTypeInsn(Opcodes.CHECKCAST, Type.getType(returnDescriptor).getInternalName());
        target.visitInsn(Opcodes.ARETURN);
    }
}
