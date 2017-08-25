package org.deflaker.inst;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class TestFailureLogClassVisitor extends ClassVisitor{

	public TestFailureLogClassVisitor(ClassVisitor cv) {
		super(Opcodes.ASM5, cv);
	}
	String className;
	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		super.visit(version, access, name, signature, superName, interfaces);
		this.className = name;
	}
	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		if ((this.className.equals("org/junit/Assert") && name.equals("fail") && desc.equals("(Ljava/lang/String;)V"))
				|| this.className.equals("org/testng/Assert") && name.equals("fail") && !desc.equals("()V")) {
			return new FailLogMethodVisitor(super.visitMethod(access, name, desc, signature, exceptions));
		}
		return super.visitMethod(access, name, desc, signature, exceptions);
	}
	class FailLogMethodVisitor extends MethodVisitor{
		public FailLogMethodVisitor(MethodVisitor mv) {
			super(Opcodes.ASM5,mv);
		}
		@Override
		public void visitCode() {
			super.visitCode();
			super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/deflaker/debug/TestFailureCatcher", "catchTestFailure", "()V", false);
		}
	}
}
