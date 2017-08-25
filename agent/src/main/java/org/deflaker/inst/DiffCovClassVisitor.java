package org.deflaker.inst;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.LinkedList;

import org.deflaker.DiffCovClassFileTransformer;
import org.deflaker.Premain;
import org.deflaker.diff.ClassInfo;
import org.deflaker.diff.Edit;
import org.deflaker.diff.ClassInfo.MethodInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.TraceClassVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DiffCovClassVisitor extends ClassVisitor {

	private ClassInfo ci;

	public DiffCovClassVisitor(ClassVisitor cv, ClassInfo newClass) {
		super(ASM5, cv);
		this.ci = newClass;
		if (newClass == null && DiffCovClassFileTransformer.ALL_COVERAGE) {
			lines = new HashSet<Integer>();
		}
	}

	boolean hasClinit;
	String className;
	boolean hasFrames;

	boolean isMock;
	boolean fixLdcClass;
	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		if (version >= 100 || version <= 50)
			hasFrames = false;
		else
			hasFrames = true;
		this.fixLdcClass = (version & 0xFFFF) < V1_5;
		isMock = superName != null && superName.startsWith("mockit");

//		if ((access & Opcodes.ACC_ANNOTATION) == 0 && (access & Opcodes.ACC_ENUM) == 0 && (access & Opcodes.ACC_INTERFACE) == 0)
//			if (interfaces == null) {
//				interfaces = new String[] { "java/org/deflaker/runtime/TrackedClass" };
//			} else {
//				boolean found = false;
//				for(String s : interfaces)
//					if(s.equals("java/org/deflaker/runtime/TrackedClass"))
//						found = true;
//				if (!found) {
//					String[] newIfaces = new String[interfaces.length + 1];
//					System.arraycopy(interfaces, 0, newIfaces, 0, interfaces.length);
//					newIfaces[interfaces.length] = "java/org/deflaker/runtime/TrackedClass";
//					interfaces = newIfaces;
//				}
//			}
		super.visit(version, access, name, signature, superName, interfaces);
		this.className = name;
	}
	
	@Override
	public void visitEnd() {

		LinkedList<Integer> editedLines = new LinkedList<Integer>();
		super.visitField(ACC_STATIC, "$$deflaker$$LineCov", "Z", null, 0);

		if(DiffCovClassFileTransformer.ALL_COVERAGE)
		{
			for(Integer i : lines)
			{
				editedLines.add(i);
				super.visitField(ACC_STATIC, "$$deflaker$$LineCov" + i, "Z", null, 0);
			}
		}
		else
			for (Edit e : ci.edits)
				for (int i = e.getEditStart(); i <= e.getEditEnd(); i++) {
					editedLines.add(i);
					super.visitField(ACC_STATIC, "$$deflaker$$LineCov" + i, "Z", null, 0);
				}

		if(ci.newMethods != null)
			for(int i = 0; i < ci.newMethods.size(); i++)
			{
				super.visitField(ACC_STATIC, "$$deflaker$$MethodCov" + i, "Z", null, 0);
			}
		MethodVisitor mv = super.visitMethod(ACC_STATIC | ACC_PUBLIC, "$$deflaker$$GetAndResetLineCov$$", "()[Z", null, null);
		InstructionAdapter ia = new InstructionAdapter(mv);
		mv.visitCode();
		ia.iconst(editedLines.size());
		mv.visitIntInsn(NEWARRAY, T_BOOLEAN);
		int c = 0;
		for (int i : editedLines) {
			mv.visitInsn(DUP);
			ia.iconst(c);
			mv.visitFieldInsn(GETSTATIC, className, "$$deflaker$$LineCov" + i, "Z");
			mv.visitInsn(BASTORE);
			mv.visitInsn(ICONST_0);
			mv.visitFieldInsn(PUTSTATIC, className, "$$deflaker$$LineCov" + i, "Z");
			c++;
		}
		mv.visitInsn(ICONST_0);
		mv.visitFieldInsn(PUTSTATIC, className, "$$deflaker$$LineCov", "Z");
		mv.visitInsn(ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		mv = super.visitMethod(ACC_STATIC | ACC_PUBLIC, "$$deflaker$$GetAndResetMethodCov$$", "()[Z", null, null);
		ia = new InstructionAdapter(mv);
		mv.visitCode();
		if (ci.newMethods == null) {
			ia.aconst(null);
		} else {
			ia.iconst(ci.newMethods.size());
			mv.visitIntInsn(NEWARRAY, T_BOOLEAN);
			for (int i =0;  i < ci.newMethods.size(); i++) {
				mv.visitInsn(DUP);
				ia.iconst(i);
				mv.visitFieldInsn(GETSTATIC, className, "$$deflaker$$MethodCov" + i, "Z");
				mv.visitInsn(BASTORE);
				mv.visitInsn(ICONST_0);
				mv.visitFieldInsn(PUTSTATIC, className, "$$deflaker$$MethodCov" + i, "Z");
			}
		}
		mv.visitInsn(ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		mv = super.visitMethod(ACC_STATIC | ACC_PUBLIC, "$$deflaker$$GetMethodCovNames$$", "()[Ljava/lang/String;", null, null);
		ia = new InstructionAdapter(mv);
		mv.visitCode();
		if (ci.newMethods == null) {
			ia.aconst(null);
		} else {

			ia.iconst(ci.newMethods.size());
			mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
			for (int i =0; i < ci.newMethods.size(); i++) {
				mv.visitInsn(DUP);
				ia.iconst(i);
				ia.aconst(ci.newMethods.get(i).toString());
				mv.visitInsn(AASTORE);
			}
		}
		mv.visitInsn(ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		
		mv = super.visitMethod(ACC_STATIC | ACC_PUBLIC, "$$deflaker$$GetLineCovLines$$", "()[I", null, null);
		ia = new InstructionAdapter(mv);
		mv.visitCode();
		ia.iconst(editedLines.size());
		mv.visitIntInsn(NEWARRAY, T_INT);
		c = 0;
		for (int i : editedLines) {
			mv.visitInsn(DUP);
			ia.iconst(c);
			ia.iconst(i);
			mv.visitInsn(IASTORE);
			c++;
		}
		mv.visitInsn(ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		if(ci.newMethods != null)
			for(int i=0; i <ci.newMethods.size(); i++)
			{
				mv = super.visitMethod(ACC_STATIC, "$$deflaker$$MethodCovHit" + i, "()V", null, null);
				mv.visitCode();
				Label ok = new Label();
				mv.visitFieldInsn(GETSTATIC, className, "$$deflaker$$MethodCov" + i, "Z");
				mv.visitJumpInsn(IFNE, ok);
				mv.visitInsn(ICONST_1);
				mv.visitFieldInsn(PUTSTATIC, className, "$$deflaker$$MethodCov" + i, "Z");
				mv.visitFieldInsn(GETSTATIC, className, "$$deflaker$$LineCov", "Z");
				mv.visitJumpInsn(IFNE, ok);
				mv.visitInsn(ICONST_1);
				mv.visitFieldInsn(PUTSTATIC, className, "$$deflaker$$LineCov", "Z");
				if(fixLdcClass)
				{
					mv.visitLdcInsn(className.replace("/", "."));
					mv.visitInsn(Opcodes.ICONST_0);
					mv.visitLdcInsn(className.replace("/", "."));
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
				}
				else
					mv.visitLdcInsn(Type.getObjectType(className));
				mv.visitMethodInsn(INVOKESTATIC, "java/org/deflaker/runtime/DiffCovAgent", "registerHit", "(Ljava/lang/Class;)V", false);
				mv.visitLabel(ok);
				if (hasFrames)
					mv.visitFrame(F_NEW, 0, null, 0, null);
				mv.visitInsn(RETURN);
				mv.visitMaxs(0, 0);
				mv.visitEnd();			}
		for (int i : editedLines) {
			mv = super.visitMethod(ACC_STATIC, "$$deflaker$$LineCovHit" + i, "()V", null, null);
			mv.visitCode();
			Label ok = new Label();
			mv.visitFieldInsn(GETSTATIC, className, "$$deflaker$$LineCov" + i, "Z");
			mv.visitJumpInsn(IFNE, ok);
			mv.visitInsn(ICONST_1);
			mv.visitFieldInsn(PUTSTATIC, className, "$$deflaker$$LineCov" + i, "Z");
			mv.visitFieldInsn(GETSTATIC, className, "$$deflaker$$LineCov", "Z");
			mv.visitJumpInsn(IFNE, ok);
			mv.visitInsn(ICONST_1);
			mv.visitFieldInsn(PUTSTATIC, className, "$$deflaker$$LineCov", "Z");
			if(fixLdcClass)
			{
				mv.visitLdcInsn(className.replace("/", "."));
				mv.visitInsn(Opcodes.ICONST_0);
				mv.visitLdcInsn(className.replace("/", "."));
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
			}
			else
				mv.visitLdcInsn(Type.getObjectType(className));
			mv.visitMethodInsn(INVOKESTATIC, "java/org/deflaker/runtime/DiffCovAgent", "registerHit", "(Ljava/lang/Class;)V", false);
			mv.visitLabel(ok);
			if (hasFrames)
				mv.visitFrame(F_NEW, 0, null, 0, null);
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		super.visitEnd();
	}

	HashSet<Integer> lines;
	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		if (name.equals("<clinit>"))
			hasClinit = true;
		MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
		final MethodVisitor forMN = mv;
		return new MethodNode(Opcodes.ASM5, access, name, desc, signature, exceptions) {
			@Override
			public void visitEnd() {
				super.visitEnd();
				AbstractInsnNode insn = this.instructions.getFirst();
				boolean trackFullMethod = false;
				if(ci.newMethods != null)
				{
					int idx = ci.newMethods.indexOf(new ClassInfo.MethodInfo(desc,name,className));
					if (idx >= 0) {
						trackFullMethod = true;
						MethodInsnNode logLine = new MethodInsnNode(INVOKESTATIC, className, "$$deflaker$$MethodCovHit" + idx, "()V", false);
						this.instructions.insert(logLine);
					}
				}
				while (insn != null) {
					if (insn instanceof LineNumberNode) {
						if(!ci.containsEditedLine(((LineNumberNode) insn).line) && trackFullMethod)
						{
							ci.edits.add(new Edit(((LineNumberNode) insn).line, ((LineNumberNode) insn).line));
						}
						if (DiffCovClassFileTransformer.ALL_COVERAGE || ci.containsEditedLine(((LineNumberNode) insn).line)) {
							if(DiffCovClassFileTransformer.ALL_COVERAGE)
								lines.add(((LineNumberNode) insn).line);
							if(ci.codeLinesEdited.add(((LineNumberNode) insn).line))
							{
								Premain.haveUpdateDiffs = true;
							}
							// Look ahead to see if there is a frame coming up
							AbstractInsnNode next = insn.getNext();
							while (next instanceof LineNumberNode || next instanceof LabelNode)
								next = next.getNext();
							MethodInsnNode logLine = new MethodInsnNode(INVOKESTATIC, className, "$$deflaker$$LineCovHit" + ((LineNumberNode) insn).line, "()V", false);
							if (next instanceof FrameNode) {
								// Insert after next
								this.instructions.insert(next, logLine);
							}
							else if(next instanceof TypeInsnNode && next.getOpcode() == Opcodes.NEW)
							{
								AbstractInsnNode prior = insn.getPrevious();
								while(prior.getType() == AbstractInsnNode.LABEL && prior.getPrevious() != null)
									prior = prior.getPrevious();
								this.instructions.insertBefore(prior, logLine);
							}
							else
								this.instructions.insert(insn, logLine);
						}
					}
					insn = insn.getNext();
				}
				this.accept(forMN);
			}
		};
	}
	
	protected String sanitizeDesc(String desc) {
		return desc.replace('(', '_').replace(')','_').replace('$','_').replace('[','_').replace(';', '_');
	}

	public static void main(String[] args) throws Throwable {
		System.setProperty("diffcov.allCoverage","true");
		File clazz = new File("z.class");
		ClassReader cr1 = new ClassReader(new FileInputStream(clazz));
		PrintWriter pw = new PrintWriter(new FileWriter("z.txt"));
		TraceClassVisitor tcv = new TraceClassVisitor(pw);
//		cr1.accept(tcv, ClassReader.EXPAND_FRAMES);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cr1.accept(new DiffCovClassVisitor(cw, null), ClassReader.EXPAND_FRAMES);
		
		cr1 = new ClassReader(cw.toByteArray());
		cr1.accept(new TraceClassVisitor(pw), ClassReader.EXPAND_FRAMES);
	}
}
