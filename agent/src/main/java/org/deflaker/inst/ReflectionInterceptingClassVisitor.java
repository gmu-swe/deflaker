package org.deflaker.inst;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class ReflectionInterceptingClassVisitor extends ClassVisitor {

	public ReflectionInterceptingClassVisitor(ClassVisitor cv) {
		super(Opcodes.ASM5, cv);
	}

	String className;

	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		this.className = name;
		super.visit(version, access, name, signature, superName, interfaces);
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
		ReflectionInterceptingMethodVisitor rmv = new ReflectionInterceptingMethodVisitor(mv, className, name, desc);
		return rmv;
	}
}
