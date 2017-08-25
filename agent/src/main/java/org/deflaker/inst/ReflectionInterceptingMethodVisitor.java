package org.deflaker.inst;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class ReflectionInterceptingMethodVisitor extends MethodVisitor {

	private String className;

	private String name;
	private String desc;

	private boolean isGetFields;
	private boolean isGetInterfaces;

	public ReflectionInterceptingMethodVisitor(MethodVisitor mv, String className, String name, String desc) {
		super(Opcodes.ASM5, mv);
		this.className = className;
		this.name = name;
		this.desc = desc;
		this.isGetFields = name.equals("getDeclaredFields") || name.equals("getFields");
		this.isGetInterfaces = name.equals("getInterfaces");
	}

	@Override
	public void visitInsn(int opcode) {
		if (opcode == Opcodes.ARETURN) {
			if (isGetFields) {
				super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/org/deflaker/runtime/ReflectionMasker", "removeCovFields", "([Ljava/lang/reflect/Field;)[Ljava/lang/reflect/Field;", false);
			} else if (isGetInterfaces) {
				super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/org/deflaker/runtime/ReflectionMasker", "removeCovInterfaces", "([Ljava/lang/Class;)[Ljava/lang/Class;", false);

			}
		}
		super.visitInsn(opcode);
	}

}
