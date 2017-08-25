package org.deflaker.inst;

import org.deflaker.DiffCovClassFileTransformer;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

public class ClassCovMethodVisitor extends MethodVisitor {

	private String className, name, desc;
	private boolean isStatic;
	private boolean isPriv, fixLdcClass, reportCoverage, isBackupCoverage;

	private boolean isIgnored;
	
	public ClassCovMethodVisitor(MethodVisitor mv, int acc, String className, String name, String desc, boolean fixLdcClass, boolean reportCoverage, boolean isBackupCoverage) {
		super(ASM5, mv);
		this.className = className;
		this.name = name;
		this.desc = desc;
		this.isStatic = (acc & ACC_STATIC) != 0;
		this.isPriv = (acc & ACC_PRIVATE) != 0;
		this.fixLdcClass = fixLdcClass;
		this.reportCoverage = reportCoverage;
		this.isBackupCoverage = isBackupCoverage;
		this.isIgnored = className.equals("com/google/firebase/database/snapshot/ChildrenNode")
				||className.equals("com/google/firebase/database/snapshot/EmptyNode"); //stupid hack for old java 8 issues
	}

	@Override
	public void visitInsn(int opcode) {
		if (opcode == Opcodes.RETURN && !this.isIgnored && name.equals("<clinit>")) {
			mv.visitFieldInsn(GETSTATIC, className, "$$deflaker$$ClassCov", "Ljava/org/deflaker/runtime/ClassProbe;");
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/org/deflaker/runtime/ClassProbe", "hit", "()V", false);
		}
		super.visitInsn(opcode);
	}
	
	@Override
	public void visitCode() {
		super.visitCode();
		if(name.equals("<clinit>"))
		{
			if (reportCoverage) {
				String probeType = "java/org/deflaker/runtime/ClassProbe";
				if(isBackupCoverage)
					probeType = "java/org/deflaker/runtime/BackupClassProbe";

				mv.visitTypeInsn(NEW, probeType);
				mv.visitInsn(DUP);
				if (fixLdcClass) {
					mv.visitLdcInsn(className.replace("/", "."));
					mv.visitInsn(Opcodes.ICONST_0);
					mv.visitLdcInsn(className.replace("/", "."));
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
				} else
					mv.visitLdcInsn(Type.getObjectType(className));
				mv.visitMethodInsn(INVOKESPECIAL, probeType, "<init>", "(Ljava/lang/Class;)V", false);
			} else {
				mv.visitTypeInsn(NEW, "java/org/deflaker/runtime/DisabledClassProbe");
				mv.visitInsn(DUP);
				mv.visitMethodInsn(INVOKESPECIAL, "java/org/deflaker/runtime/DisabledClassProbe", "<init>", "()V", false);
			}
			mv.visitFieldInsn(PUTSTATIC, className, "$$deflaker$$ClassCov", "Ljava/org/deflaker/runtime/ClassProbe;");
		}
		if (!isPriv && (isStatic || name.equals("<init>")) && !name.equals("<clinit>"))
		{
			if (!this.isIgnored && reportCoverage) {
				mv.visitFieldInsn(GETSTATIC, className, "$$deflaker$$ClassCov", "Ljava/org/deflaker/runtime/ClassProbe;");
				mv.visitMethodInsn(INVOKEVIRTUAL, "java/org/deflaker/runtime/ClassProbe", "hit", "()V", false);
			}
		}
	}

//	@Override
//	public void visitFieldInsn(int opcode, String owner, String name, String desc) {
//		if ((opcode == GETSTATIC || opcode == PUTSTATIC) && !owner.equals(className) && !DiffCovClassFileTransformer.isExcluded(owner))
//		{
//			mv.visitFieldInsn(GETSTATIC, owner, "$$deflaker$$ClassCov", "Ljava/org/deflaker/runtime/ClassProbe;");
//			mv.visitMethodInsn(INVOKEVIRTUAL, "java/org/deflaker/runtime/ClassProbe", "hit", "()V", false);
//		}
//		super.visitFieldInsn(opcode, owner, name, desc);
//	}
}
