package org.deflaker.listener;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedList;

import org.deflaker.debug.TestFailureCatcher;
import org.deflaker.runtime.Base64;
import org.deflaker.runtime.MySQLLogger;
import org.deflaker.runtime.MySQLLogger.TestResult;
import org.deflaker.runtime.SharedHolder;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

public class TestNGExecutionListener implements ITestListener {
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
