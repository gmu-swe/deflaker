package org.deflaker.listener;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.org.deflaker.runtime.BackupClassProbe;
import java.org.deflaker.runtime.ClassProbe;
import java.org.deflaker.runtime.DiffCovAgent;
import java.util.HashSet;
import java.util.LinkedList;

import org.deflaker.debug.TestFailureCatcher;
import org.deflaker.runtime.Base64;
import org.deflaker.runtime.MySQLLogger;
import org.deflaker.runtime.MySQLLogger.TestResult;
import org.deflaker.runtime.SharedHolder;
import org.deflaker.runtime.TestLineHit;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

public class TestNGListener implements ITestListener {
	static FirebaseLogger firebase;
	static MySQLLogger delegate;
	String lastTestClass = null;
	LinkedList<TestResult> methods = new LinkedList<TestResult>();
	TestResult thisMethod;
	static String OUTPUT_FILE = System.getProperty("diffcov.log", "coverage.diff.log");
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
	static {
		if (System.getProperty("diffcov.mysql") != null && System.getProperty("diffcov.mysqllight") == null) {
			delegate = MySQLLogger.instance();
			delegate.testID = Integer.valueOf(System.getProperty("diffcov.studyid"));
			if (delegate.uuid == null)
				delegate.init("DummyProject", null, "" + delegate.testID);
		}
		if (System.getenv("TRAVIS") != null) {
			// set up firebase
			System.out.println("Connecting to firebase");
			if (SharedHolder.logger == null)
				SharedHolder.logger = new FirebaseLogger();
			firebase = (FirebaseLogger) SharedHolder.logger;
		}
	}
	String className;
	String methodName;
	TestResult res;

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

	static FileWriter logger;
	static {
		try {
			logger = new FileWriter(OUTPUT_FILE);
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

				@Override
				public void run() {
					try {
						logger.close();
						if (firebase != null)
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

	private void finishedClass() {
		if(res == null)
			return;
		if (res.reported)
			return;

		res.reported = true;
		if (res.finished == 0)
			res.finished = System.currentTimeMillis();
		if (res.startTime == 0 && res.nMethods == 0)
			res.startTime = res.finished;

		if (firebase != null)
			firebase.log(res);

		logCoverage();
		res.methods = methods;
		methods = new LinkedList<TestResult>();

		if (DEFLAKER_RESULT_LOGGER != null)
			for(TestResult ts : res.methods)
				try {
					DEFLAKER_RESULT_LOGGER.write(Base64.toBase64(res.name.replace("deflaker.inForkRerun$", "") + "#" + ts.name) + "\t" + (ts.failed ? "FAILED" : "OK")+"\n");
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

	@Override
	public void onTestStart(ITestResult description) {
		if (!getClassName(description).equals(lastTestClass)) {
			// we are doing another test class
			// System.out.println("Starting new test class");
			if (res != null)
				finishedClass();
			res = new TestResult(getClassName(description));
			lastTestClass = getClassName(description);
		}
		className = getClassName(description);
		methodName = getMethodName(description);
		TestFailureCatcher.testName = className+"."+methodName;

		// System.out.println(">>Start" + className+ "."+methodName);
		TestResult m = new TestResult(getMethodName(description));
		m.startTime = System.currentTimeMillis();
		thisMethod = m;
		methods.add(m);

		res.nMethods++;
	}

	boolean methodReported;

	private String getMethodName(ITestResult description) {
		return description.getMethod().getMethodName();
	}

	private String getClassName(ITestResult description) {
		String ret = description.getTestClass().getName();
		if(System.getProperty("deflaker.inProcessRerun") != null)
			return "deflaker.inProcessRerun$"+ret;
		else if(System.getProperty("deflaker.isInRerunFork") != null)
			return "deflaker.inForkRerun$"+ret;
		return ret;
	}

	@Override
	public void onTestSuccess(ITestResult result) {
		onFinish(result);
	}

	@Override
	public void onTestFailure(ITestResult result) {
		res.nFailures++;
		res.failed = true;
		if(thisMethod != null)
		{
			thisMethod.failed = true;
			if(result.getThrowable() != null)
				thisMethod.exception = result.getThrowable().toString();
		}
		onFinish(result);
	}

	void onFinish(ITestResult description) {
		if (thisMethod != null)
			thisMethod.endTime = System.currentTimeMillis();
		// thisMethod.failed = false;
		methodReported = true;
		// System.out.println(">>>"+description.getDisplayName() +
		// "Finished\n");
		logCoverage();
	}

	@Override
	public void onTestSkipped(ITestResult result) {
		if(res != null)
		{
			res.nSkips++;
			if(thisMethod != null)
			{
				thisMethod.endTime = System.currentTimeMillis();
				thisMethod.skipped = true;
				if(result.getThrowable() != null)
					thisMethod.exception = result.getThrowable().toString();
			}
			onFinish(result);
		}
	}

	@Override
	public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
		onFinish(result);
	}

	@Override
	public void onStart(ITestContext context) {

	}

	@Override
	public void onFinish(ITestContext context) {
		finishedClass();

	}

}
