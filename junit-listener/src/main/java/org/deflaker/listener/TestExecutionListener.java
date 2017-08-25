package org.deflaker.listener;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedList;

import net.sourceforge.cobertura.coveragedata.CoverageDataFileHandler;
import net.sourceforge.cobertura.coveragedata.ProjectData;
import net.sourceforge.cobertura.coveragedata.TouchCollector;

import org.deflaker.debug.TestFailureCatcher;
import org.deflaker.runtime.Base64;
import org.deflaker.runtime.MySQLLogger;
import org.deflaker.runtime.MySQLLogger.TestResult;
import org.deflaker.runtime.SharedHolder;
import org.jacoco.agent.rt.IAgent;
import org.jacoco.agent.rt.RT;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

public class TestExecutionListener extends RunListener {

	static String OUTPUT_FILE = System.getProperty("diffcov.log", "coverage.diff.log");
	static MySQLLogger delegate;
	static FirebaseLogger firebase;
	static boolean IS_JACOCO_PER_TEST = System.getProperty("diffcov.jacocoPerTest") != null;
	static boolean IS_COBERTURA_PER_TEST = System.getProperty("diffcov.coberturaPerTest") != null;
	static FileWriter DEFLAKER_RESULT_LOGGER;

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
			if(lastTestClass == null || description == null || "null".equals(lastTestClass) || "classes".equals(lastTestClass))
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
		if (thisMethod != null) {
			thisMethod.endTime = System.currentTimeMillis();
		}
		methodReported = true;
//		System.out.println(">>>"+description.getDisplayName() + "Finished\n");
		if (description.getChildren() != null && description.getChildren().size() == 1) {
			Description child = description.getChildren().get(0);
			long time = Long.valueOf(child.getDisplayName());
			res.finished = time;
		}
		logCoverage();
	}

	static Field covFieldToReset;
	public void logCoverage()
	{
		if(IS_JACOCO_PER_TEST)
		{
			IAgent agent = RT.getAgent();
			agent.setSessionId(className+"."+methodName);
			try {
				agent.dump(true);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		else if(IS_COBERTURA_PER_TEST)
		{
			ProjectData d = ProjectData.getGlobalProjectData();
			if(covFieldToReset == null)
			{
				try {
					covFieldToReset = ProjectData.class.getDeclaredField("globalProjectData");
				} catch (NoSuchFieldException e) {
				} catch (SecurityException e) {
				}
				covFieldToReset.setAccessible(true);
			}
			try {
				covFieldToReset.set(null, new ProjectData());
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
			TouchCollector.applyTouchesOnProjectData(d);
			CoverageDataFileHandler.saveCoverageData(d, new File("target/cobertura-" + className + "." + methodName + ".ser"));

		}		
	}
	int nErrors;

	String lastFinishedClass = null;

	@Override
	public void testRunFinished(Result result) throws Exception {
		if(res == null)
			return;
//		res.nFailures = result.getFailureCount();
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
		if(failure.getDescription().getChildren() != null && !failure.getDescription().getChildren().isEmpty())
		{
			if (!getClassName(failure.getDescription()).equals(lastTestClass)) {
				thisMethod = null;
				if (res != null)
					finishedClass();
				res = new TestResult(getClassName(failure.getDescription()));
				lastTestClass = getClassName(failure.getDescription());
			}
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
		res.stderr.append("Failed on  " + failure.getTestHeader() + ": " + failure.getMessage() + Arrays.toString(failure.getException().getStackTrace()));
	}

	TestResult res;

	/**
	 * Called when a test will not be run, generally because a test method is
	 * annotated with Ignore.
	 * */
	public void testIgnored(Description description) throws java.lang.Exception {
//		System.out.println("Execution of test case ignored : " + description.getMethodName());
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
