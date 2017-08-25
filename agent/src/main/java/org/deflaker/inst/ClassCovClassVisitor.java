package org.deflaker.inst;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;


public class ClassCovClassVisitor extends ClassVisitor {

	boolean reportCoverage;
	boolean isBackupCoverage;
	public ClassCovClassVisitor(ClassVisitor cv, boolean reportCoverage) {
		super(Opcodes.ASM5, cv);
		this.reportCoverage = reportCoverage;
	}
	public ClassCovClassVisitor(ClassVisitor cv, boolean reportCoverage, boolean isBackup) {
		this(cv,reportCoverage);
		this.isBackupCoverage = isBackup;
	}

	private String className;
	private boolean isInterface;
	private boolean hasClinit;

	private boolean fixLdcClass;

	private boolean isMock;
	
	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		if (this.reportCoverage && (access & Opcodes.ACC_ANNOTATION) == 0 && (access & Opcodes.ACC_ENUM) == 0)
			isMock = superName != null && superName.startsWith("mockit");
			if (interfaces == null) {
				if(!isMock)
					interfaces = new String[] { "java/org/deflaker/runtime/TrackedClass" , "java/org/deflaker/runtime/TrackedClassLevelClass" };
			} else {
				for(String s : interfaces)
					if(s.equals("org/powermock/core/classloader/PowerMockModified"))
						isMock = true;
				if (!isMock) {
					String[] newIfaces = new String[interfaces.length + 2];
					System.arraycopy(interfaces, 0, newIfaces, 0, interfaces.length);
					newIfaces[interfaces.length] = "java/org/deflaker/runtime/TrackedClass";
					newIfaces[interfaces.length + 1] = "java/org/deflaker/runtime/TrackedClassLevelClass";
					interfaces = newIfaces;
				}
			}
		super.visit(version, access, name, signature, superName, interfaces);
		this.className = name;
		this.fixLdcClass = (version & 0xFFFF) < V1_5;

		this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
		if (name.equals("<clinit>"))
			hasClinit = true;
		if(isMock)
			return mv;
		mv = new ClassCovMethodVisitor(mv, access, className, name, desc, fixLdcClass, reportCoverage, isBackupCoverage);
		return mv;
	}

	@Override
	public void visitEnd() {
		super.visitEnd();
		if(isMock)
			return;
		super.visitField(ACC_STATIC | ACC_FINAL | ACC_PUBLIC, "$$deflaker$$ClassCov", "Ljava/org/deflaker/runtime/ClassProbe;", null, null);
		if (!hasClinit) {
			MethodVisitor mv = super.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
			mv.visitCode();
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
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

	}
}
