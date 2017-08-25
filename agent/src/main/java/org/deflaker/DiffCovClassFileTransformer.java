package org.deflaker;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

import org.deflaker.diff.ClassInfo;
import org.deflaker.diff.EditedFile;
import org.deflaker.inst.ClassCovClassVisitor;
import org.deflaker.inst.DiffCovClassVisitor;
import org.deflaker.inst.ReflectionInterceptingClassVisitor;
import org.deflaker.inst.TestFailureLogClassVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

public class DiffCovClassFileTransformer implements ClassFileTransformer {

	public static boolean isExcluded(String internalName)
	{
		return 
				internalName.startsWith("sun/")  || internalName.startsWith("java") || internalName.startsWith("com/sun")
				|| internalName.startsWith("mockit/")
				|| (internalName.startsWith("org/deflaker") && !internalName.startsWith("org/deflaker/test")
						|| internalName.startsWith("org/apache/log4j") ||
						internalName.startsWith("org/powermock"));
	}
	final static boolean ONLY_CLASS = Boolean.valueOf(System.getProperty("diffcov.onlyClass","false"));
	public final static boolean ALL_COVERAGE = Boolean.valueOf(System.getProperty("diffcov.allCoverage","false"));

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
		ClassReader cr = new ClassReader(classfileBuffer);
		try {
			if(classBeingRedefined != null)
			{
				if(classBeingRedefined == Class.class)
				{
					ClassWriter cw = new ClassWriter(0);
					ReflectionInterceptingClassVisitor cv = new ReflectionInterceptingClassVisitor(cw);
					cr.accept(cv, 0);
					return cw.toByteArray();
				}
				return null;
			}
			if(cr.getClassName().equals("org/junit/Assert") || cr.getClassName().equals("org/testng/Assert"))
			{
				//Hack junit fail to record heap dumps
				ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				ClassVisitor cv = new TestFailureLogClassVisitor(cw);
				cr.accept(cv, 0);
				return cw.toByteArray();
			}
			if(isExcluded(cr.getClassName()))
				return null;
			ClassInfo diffs = Premain.diffs.get(cr.getClassName());

			if(ALL_COVERAGE)
			{
				if(!Premain.classes.contains(cr.getClassName()))
				{
					ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
					ClassCovClassVisitor cv = new ClassCovClassVisitor(cw, false);
					cr.accept(cv, 0);
					return cw.toByteArray();
				}
				else if(ONLY_CLASS)
				{
					ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
					ClassCovClassVisitor cv = new ClassCovClassVisitor(cw, true);
					cr.accept(cv, 0);
					return cw.toByteArray();
				}
				else
				{
					ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
					boolean isInterface = (cr.getAccess() & Opcodes.ACC_INTERFACE) !=0;
					ClassVisitor cv;
					if(isInterface)
						cv = new ClassCovClassVisitor(cw, true);
					else
						cv = new DiffCovClassVisitor(cw, null);
					cr.accept(cv, 0);
//					File f= new File("debug");
//					if(!f.exists()) f.mkdirs();
//					FileOutputStream fos = new FileOutputStream(new File("debug/"+cr.getClassName().replace('/', '.')+".class"));
//					fos.write(cw.toByteArray());
//					fos.close();
					return cw.toByteArray();

				}
			}
			else if (diffs != null && !diffs.hasStructuralProblems && !ONLY_CLASS) {
				ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				boolean isInterface = (cr.getAccess() & Opcodes.ACC_INTERFACE) !=0;
				ClassVisitor cv;
				if(isInterface)
					cv = new ClassCovClassVisitor(cw, true);
				else
					cv = new DiffCovClassVisitor(new ClassCovClassVisitor(cw, true, true), diffs);
				cr.accept(cv, 0);
//				File f= new File("debug");
//				if(!f.exists()) f.mkdirs();
//				FileOutputStream fos = new FileOutputStream(new File("debug/"+cr.getClassName().replace('/', '.')+".class"));
//				fos.write(cw.toByteArray());
//				fos.close();
				return cw.toByteArray();
			} else if(diffs != null){
//				System.err.println("WARN: DEBUG: No diff for " + className);
				// We need to revert to class-level coverage for this class :/
				ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				ClassCovClassVisitor cv = new ClassCovClassVisitor(cw, diffs != null);
				cr.accept(cv, 0);
//				FileOutputStream fos = new FileOutputStream(new File("debug/"+cr.getClassName().replace('/', '.')+".class"));
//				fos.write(cw.toByteArray());
//				fos.close();
				return cw.toByteArray();
			}
		} catch (Throwable t) {
			t.printStackTrace();
			throw new IllegalClassFormatException();
		}
		return null;
	}
}
