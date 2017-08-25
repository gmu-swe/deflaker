package java.org.deflaker.runtime;

import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;

public class DiffCovAgent {
	public static HashSet<Class> activeSet = new HashSet<Class>();
	public static HashSet<Class> activeClassSet = new HashSet<Class>();
	public static HashSet<Class> activeBackupClassSet = new HashSet<Class>();

	
	public static void registerHit(Class c) {
		synchronized (activeSet) {
			activeSet.add(c);
		}
	}

	public static void registerClassHit(Class c) {
		synchronized (activeClassSet) {
			activeClassSet.add(c);
		}
	}
	
	public static void registerBackupClassHit(Class c) {
		synchronized (activeBackupClassSet) {
			activeBackupClassSet.add(c);
		}
	}


	public static void registerClassHitReflection(Class c) {
		if(!TrackedClassLevelClass.class.isAssignableFrom(c) || c.isAnnotation() || c.isEnum()) //TODO if this is not an intrinsic, come up with a faster way... like abusing a bit on the object header with unsafe
			return;
//		if(c == TrackedClassLevelClass.class)
//			return;
		synchronized (activeClassSet) {
			activeClassSet.add(c);
		}
	}
	
	static {
		if (System.getProperty("diffCovOnShutdown") != null)
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

				@Override
				public void run() {
					System.out.println("Diff results");
					for (Class c : activeSet) {
						try {
							int[] linesDiff = (int[]) c.getDeclaredMethod("$$deflaker$$GetLineCovLines$$").invoke(null);
							boolean[] linesHit = (boolean[]) c.getDeclaredMethod("$$deflaker$$GetAndResetLineCov$$").invoke(null);
							for (int i = 0; i < linesDiff.length; i++) {
								if (linesHit[i]) {
									System.out.println("++++" + c + ":" + linesDiff[i]);
								} else {
									System.out.println("----" + c + ":" + linesDiff[i]);
								}
							}
						} catch (Throwable e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
					for(Class c : activeClassSet)
					{
						System.out.println("Classcov hit: " + c);
					}
				}
			}));
	}
}
