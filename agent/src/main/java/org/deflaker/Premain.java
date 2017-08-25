package org.deflaker;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Scanner;

import org.deflaker.diff.ClassInfo;
import org.deflaker.diff.EditedFile;


public class Premain {
	public static boolean DEBUG = false;
	public static HashMap<String, ClassInfo> diffs;
	public static HashSet<String> classes;
	public static boolean haveUpdateDiffs = false;
	public static void premain(String args, Instrumentation inst) {
		/*
		 * First, make sure we can find what the diff'ed code is
		 */
		if(args != null)
		{
			File diffFile = null;
			for(String s : args.split(","))
			{
				String[] d = s.split("=");
				if(d[0].equalsIgnoreCase("diffFile"))
					diffFile = new File(d[1]);
				else if(d[0].equalsIgnoreCase("debug"))
					DEBUG = true;
			}
			if (diffFile != null && diffFile.exists()) {
				try {
					FileInputStream fis = new FileInputStream(diffFile);
					ObjectInputStream ois = new ObjectInputStream(fis);
					diffs = (HashMap<String, ClassInfo>) ois.readObject();
					ois.close();
					final File _diffFile= diffFile;
					Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
						@Override
						public void run() {
							if (haveUpdateDiffs) {
								try {
									FileOutputStream fos = new FileOutputStream(_diffFile);
									ObjectOutputStream oos = new ObjectOutputStream(fos);
									oos.writeObject(diffs);
									oos.close();
								} catch (IOException ex) {
									ex.printStackTrace();
								}
							}
						}
					}));
				} catch (Exception ex) {
				}
			}
			else
			{
				throw new IllegalStateException("No diff file! Wanted " + diffFile);
			}
			if(DiffCovClassFileTransformer.ALL_COVERAGE)
			{
				try {
					classes = new HashSet<String>();
					Scanner s = new Scanner(new File(diffFile.getAbsolutePath()+".javafiles"));
					while(s.hasNextLine())
					{
						classes.add(s.nextLine());
					}
					s.close();
				} catch (FileNotFoundException e) {
					throw new IllegalStateException(e);
				}
				
			}
			
		}
		else
		{
			System.err.println("Didn't find any args for agent!");
		}
		
		//Change class.getdeclaredmethods and fields to hide ourselves
		inst.addTransformer(new DiffCovClassFileTransformer(), true);
		try {
			inst.retransformClasses(Class.class);
		} catch (UnmodifiableClassException e) {
			e.printStackTrace();
		}
		
		System.setProperty("org.osgi.framework.bootdelegation", "org.deflaker.*");
	}
}
