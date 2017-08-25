package org.deflaker.listener;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.org.deflaker.runtime.BackupClassProbe;
import java.org.deflaker.runtime.ClassProbe;
import java.org.deflaker.runtime.DiffCovAgent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;

import org.deflaker.debug.TestFailureCatcher;
import org.deflaker.runtime.Base64;
import org.deflaker.runtime.MySQLLogger;
import org.deflaker.runtime.MySQLLogger.TestResult;
import org.deflaker.runtime.SharedHolder;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

public class CoverageListener extends RunListener {

	static String OUTPUT_FILE = System.getProperty("diffcov.log", "coverage.diff.log");
	static MySQLLogger delegate;
	static FirebaseLogger firebase;
	static FileWriter DEFLAKER_RESULT_LOGGER;
	
	static{
		if(System.getProperty("deflaker.reportsDirectory") != null)
		{
			try {
				File dir = new File(System.getProperty("deflaker.reportsDirectory"));
				if(!dir.exists())
					dir.mkdirs();
				DEFLAKER_RESULT_LOGGER = new FileWriter(System.getProperty("deflaker.reportsDirectory")+"/rerunResults", true);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	static 
	{
		if(System.getProperty("diffcov.mysql") != null && System.getProperty("diffcov.mysqllight") == null)
		{
			delegate = MySQLLogger.instance();
			delegate.testID = Integer.valueOf(System.getProperty("diffcov.studyid"));
			if(delegate.uuid == null)
				delegate.init("DummyProject", null, ""+delegate.testID);
		}
		if(System.getenv("TRAVIS") != null)
		{
			//set up firebase
			System.out.println("Connecting to firebase");
			if(SharedHolder.logger == null)
				SharedHolder.logger = new FirebaseLogger();
			firebase = (FirebaseLogger) SharedHolder.logger;
		}
	}
	
	private String getMethodName(Description desc) {
		if (desc == null)
			return "null";
		if (desc.getMethodName() == null)
			return desc.getDisplayName();
		else
			return desc.getMethodName();
	}

	private String getClassName(Description desc) {
		if (desc == null)
			return "null";
		String ret;
		if (desc.getClassName() == null)
			ret = desc.getTestClass().getName();
		else
			ret = desc.getClassName();
		if(ret == null || ret.equals("null") || ret.equals("classes"))
			return ret;
		else if(System.getProperty("deflaker.inProcessRerun") != null)
			return "deflaker.inProcessRerun$"+ret;
		else if(System.getProperty("deflaker.isInRerunFork") != null)
			return "deflaker.inForkRerun$"+ret;
		return ret;
	}

	String lastTestClass = null;
	LinkedList<TestResult> methods = new LinkedList<TestResult>();

	@Override
	public void testRunStarted(Description description) throws Exception {
		if (!getClassName(description).equals(lastTestClass)) {
			//we are doing another test class
			if (res != null)
				finishedClass();
			methods.clear();
			lastTestClass = getClassName(description);
			if(lastTestClass == null || description == null || "null".equals(lastTestClass)|| "classes".equals(lastTestClass))
				return;
			res = new TestResult(lastTestClass);
//			if (description != null && description.getChildren() != null && description.getChildren().size() == 1) {
//				Description child = description.getChildren().get(0);
//				long time = Long.valueOf(child.getDisplayName());
//				res.startTime = time;
//			}
		}
	}

	TestResult thisMethod;

	/**
	 * Called when an atomic test is about to be started.
	 * */
	public void testStarted(Description description) throws java.lang.Exception {
		if (!getClassName(description).equals(lastTestClass)) {
			//we are doing another test class
//			System.out.println("Starting new test class");
			if (res != null)
				finishedClass();
			res = new TestResult(getClassName(description));
			lastTestClass = getClassName(description);
		}
		className = getClassName(description);

		methodName = getMethodName(description);
		TestFailureCatcher.testName = className+"."+methodName;
//		System.out.println(">>Start" + className+ "."+methodName);
		TestResult m = new TestResult(getMethodName(description));
		m.startTime = System.currentTimeMillis();
		thisMethod = m;
		methods.add(m);
		if (res.startTime == 0 && description.getChildren() != null && description.getChildren().size() == 1) {
			Description child = description.getChildren().get(0);
			long time = Long.valueOf(child.getDisplayName());
			res.startTime = time;

		}
		res.nMethods++;
	}

	boolean methodReported = false;
	@Override
	public void testFinished(Description description) throws Exception {
		if(thisMethod != null)
			thisMethod.endTime = System.currentTimeMillis();
//		thisMethod.failed = false;
		methodReported = true;
//		System.out.println(">>>"+description.getDisplayName() + "Finished\n");
		if (description.getChildren() != null && description.getChildren().size() == 1) {
			Description child = description.getChildren().get(0);
			long time = Long.valueOf(child.getDisplayName());
			res.finished = time;
		}
		logCoverage();

	}

	int nErrors;

	String lastFinishedClass = null;

	@Override
	public void testRunFinished(Result result) throws Exception {
		if(res == null)
			return;
//		if(result != null)
//			res.nFailures = result.getFailureCount();
//		if (!lastTestClass.equals(lastFinishedClass))
			finishedClass();
//		lastFinishedClass = lastTestClass;
		lastTestClass = null;
		res = null;
	}
	
	String className;
	String methodName;
	
	private void finishedClass() {
		if (res.reported)
			return;

		res.reported = true;
		if (res.finished == 0)
			res.finished = System.currentTimeMillis();
		if (res.startTime == 0 && res.nMethods == 0)
			res.startTime = res.finished;
		
		if(firebase != null)
			firebase.log(res);
		
		res.methods = methods;
		methods = new LinkedList<TestResult>();

		if (DEFLAKER_RESULT_LOGGER != null)
			for(TestResult ts : res.methods)
				try {
					DEFLAKER_RESULT_LOGGER.write(Base64.toBase64(res.name.replace("deflaker.inForkRerun$", "")+ "#" +ts.name) + "\t" + (ts.failed ? "FAILED" : "OK")+"\n");
				} catch (IOException e) {
					e.printStackTrace();
				}

		if (delegate != null)
			synchronized (delegate.insertQueue) {
				// if (!delegate.inserter.isAlive() && delegate.senderDead) {
				// delegate.senderDead = false;
				// delegate.inserter.start();
				// }
				// System.out.println("Finished and sending" + res.name);
				if (res.name != null && !"null".equals(res.name)) {
					delegate.insertQueue.add(res);
					delegate.insertQueue.notifyAll();
				}
			}
	}

	/**
	 * Called when an atomic test fails.
	 * */
	public void testFailure(Failure failure) throws java.lang.Exception {
		//If it's a failure in the BeforeClass and we're in the same JVM as an old test, res might point to the last guy?
		if (!getClassName(failure.getDescription()).equals(lastTestClass)) {
			thisMethod = null;
			if (res != null)
				finishedClass();
			res = new TestResult(getClassName(failure.getDescription()));
			lastTestClass = getClassName(failure.getDescription());
		}
		if(failure.getDescription().getChildren() != null && !failure.getDescription().getChildren().isEmpty())
		{
			//Make sure that the child method was created, it almost definitely wasn't
			String methName = getMethodName(failure.getDescription().getChildren().get(0));
			boolean found = false;
			for(TestResult m : methods)
				if(m.name.equals(methName))
					found = true;
			if(!found)
			{
				TestResult meth = new TestResult(methName);
				meth.startTime = 0;
				meth.endTime = 0;
				meth.failed = true;
				meth.exception = failure.getTrace();
				methods.add(meth);
				res.nMethods++;
			}
		}
		if(res == null)
			return;
		res.nFailures++;
		if (thisMethod != null) {
			thisMethod.exception = failure.getTrace();
			thisMethod.endTime = System.currentTimeMillis();
			thisMethod.failed = true;
		}
		res.failed = true;
//		System.out.println(">>>"+failure.getDescription());
//		System.out.println("Failed on  " + failure.getTestHeader() + ": " + failure.getMessage() + Arrays.toString(failure.getException().getStackTrace()));
	}

	public void testAssumptionFailure(Failure failure) {
		if(res == null)
			return;
		res.nSkips++;
		if (thisMethod != null) {
			thisMethod.endTime = System.currentTimeMillis();
			thisMethod.skipped = true;
		}
		res.failed = true;
		res.stderr.append("Skipped on  " + failure.getTestHeader() + ": " + failure.getMessage() + Arrays.toString(failure.getException().getStackTrace()));
	}

	TestResult res;

	/**
	 * Called when a test will not be run, generally because a test method is
	 * annotated with Ignore.
	 * */
	public void testIgnored(Description description) throws java.lang.Exception {
//		System.out.println("Execution of test case ignored : " + description.getMethodName());
	}
	public final static boolean PRECISE = System.getProperty("diffcov.precise") != null;

	void logCoverage() {
		if (className == null)
			return;
		LinkedList<String> hitMethods = new LinkedList<String>();

		LinkedList<String> hitLines = new LinkedList<String>();
		LinkedList<String> hitClasses = new LinkedList<String>();
		LinkedList<String> hitBackupClasses = new LinkedList<String>();

		HashSet<Class> activeSet = DiffCovAgent.activeSet;
		DiffCovAgent.activeSet = new HashSet<Class>();
		HashSet<Class> activeClassSet = DiffCovAgent.activeClassSet;
		DiffCovAgent.activeClassSet = new HashSet<Class>();
		HashSet<Class> activeBackupClassSet = DiffCovAgent.activeBackupClassSet;
		DiffCovAgent.activeBackupClassSet = new HashSet<Class>();

		for (Class<?> c : activeSet) {
			try {
				if(PRECISE)
				{
					Method methMethod = c.getDeclaredMethod("$$deflaker$$GetAndResetMethodCov$$");
					methMethod.setAccessible(true);
					Method methNameMethod = c.getDeclaredMethod("$$deflaker$$GetMethodCovNames$$");
					methNameMethod.setAccessible(true);
					String[] names = (String[]) methNameMethod.invoke(null);
					boolean[] methsHit = (boolean[]) methMethod.invoke(null);
					if(methsHit != null)
						for(int i =0;i<methsHit.length; i++)
						{
							if(methsHit[i])
							{
								hitMethods.add(c.getName()+"."+names[i]);
								logger.write(new TestLineHit(className, methodName, c.getName(), false, names[i]).write());
							}
						}
				}
				Method lineMethod = c.getDeclaredMethod("$$deflaker$$GetLineCovLines$$");
				lineMethod.setAccessible(true);
				Method resetMethod = c.getDeclaredMethod("$$deflaker$$GetAndResetLineCov$$");
				resetMethod.setAccessible(true);
				int[] linesDiff = (int[]) lineMethod.invoke(null);
				boolean[] linesHit = (boolean[]) resetMethod.invoke(null);
				for (int i = 0; i < linesDiff.length; i++) {
					if (linesHit[i]) {
						hitLines.add(c.getName() +":"+linesDiff[i]);
						logger.write(new TestLineHit(className, methodName, c.getName(), false, linesDiff[i]).write());
					}
				}
			} catch (Throwable e) {
				e.printStackTrace();
			}
		}
		for (Class<?> c : activeClassSet) {
			try {
				hitClasses.add(c.getName());
				logger.write(new TestLineHit(className, methodName, c.getName(), true, 0).write());
				Field f = c.getDeclaredField("$$deflaker$$ClassCov");
				f.setAccessible(true);
				ClassProbe p = (ClassProbe) f.get(null);
				p.hit = false;
			} catch (Throwable e) {
				System.err.println("Error collecting coverage of " + c);
				e.printStackTrace();
			}
		}
		for (Class<?> c : activeBackupClassSet) {
			try {
				hitBackupClasses.add(c.getName());
				logger.write(new TestLineHit(className, methodName, c.getName(), true, true, 0).write());
				Field f = c.getDeclaredField("$$deflaker$$ClassCov");
				f.setAccessible(true);
				BackupClassProbe p = (BackupClassProbe) f.get(null);
				p.hit = false;
			} catch (Throwable e) {
				System.err.println("Error collecting coverage of " + c);
				e.printStackTrace();
			}
		}
		if(firebase != null)
			firebase.logHits(className, methodName, hitClasses, hitBackupClasses, hitLines, hitMethods);
	}

//	static AsynchronousFileChannel logger;
	static FileWriter logger;
	static {
		try {
			logger = new FileWriter(new File(OUTPUT_FILE), true);
//			logger = AsynchronousFileChannel.open(new File(OUTPUT_FILE).toPath(), StandardOpenOption.APPEND);
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

				@Override
				public void run() {
					try {
//						logger.force(true);
						logger.close();
						if(firebase != null)
							firebase.awaitExit();
						if(DEFLAKER_RESULT_LOGGER != null)
							DEFLAKER_RESULT_LOGGER.close();

					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}));
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}
}
