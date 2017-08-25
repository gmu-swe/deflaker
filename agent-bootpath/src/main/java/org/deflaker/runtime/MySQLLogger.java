package org.deflaker.runtime;

import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.LinkedList;

import org.deflaker.runtime.MySQLLogger.TestEventMessage.MessageType;


public class MySQLLogger {
	int nFailures;
	int nErrors;
	long methodStart;
	boolean methodErrored;
	boolean methodFailed;

	StringBuffer stdout = new StringBuffer();
	StringBuffer stderr = new StringBuffer();
	int nMethods = 0;
	long startTime;
	long finished;
	boolean failed;
	String className;
	String methodName;

	public Connection db;
	public PreparedStatement insertTestClass;
	public LinkedList<TestResult> insertQueue = new LinkedList<TestResult>();
	


	static String OUTPUT_FILE = System.getProperty("diffcov.log", "coverage.diff.log")+".testToId";

	static FileWriter testToIdLogger;
	static {
		try {
			testToIdLogger = new FileWriter(OUTPUT_FILE, true);
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	public int testID;
	public Thread inserter = new Thread(new Runnable() {
		PreparedStatement insertOneMethod = null;
		void prepareDB()
		{
			try {
				//				System.out.println("Inserter starting");
				db = getConnection();
				insertOneMethod = db.prepareStatement("INSERT INTO test_result_test_method (test_result_test_id,name,failed,duration,exception) VALUES (?,?,?,?,?)",
						PreparedStatement.RETURN_GENERATED_KEYS);
				insertTestClass = db.prepareStatement("INSERT INTO test_result_test (test_execution_id,test,time,success,nTestMethods,start,end,nFailures,nSkipped) VALUES (?,?,?,?,?,?,?,?,?)",
						Statement.RETURN_GENERATED_KEYS);
			} catch (SQLException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
		}
		@Override
		public void run() {
			prepareDB();
			HashMap<String, Integer> testMethodIDs = new HashMap<String, Integer>();

			LinkedList<TestResult> working = new LinkedList<TestResult>();
			while (true) {
				long timeGoingToBed = System.currentTimeMillis();
				synchronized (insertQueue) {
					while (insertQueue.size() == 0) {
						try {
							//							System.out.println("Inserter waiting");
							insertQueue.wait();
						} catch (InterruptedException ex) {

						}
					}
				}
				//				System.out.println("Inserter awake");
				synchronized (insertQueue) {
					insertSenderWorking = true;
					working.addAll(insertQueue);
					insertQueue.clear();
				}
				long wokeUp = System.currentTimeMillis();
				if(wokeUp - timeGoingToBed > 18000000)
				{
					//5 hours elapsed since we last sent any comms.
					System.out.println("Re-connecting to mysql");
					try{
						System.out.println("Closing old con");
						db.close();
					}
					catch(SQLException ex)
					{
						ex.printStackTrace();
					}
					System.out.println("Re-opening con.");
					prepareDB();
				}
				try {
					for (TestResult res : working) {
						insertTestClass.setInt(1, testID);
						insertTestClass.setString(2, res.name);
						insertTestClass.setLong(3, (int) (res.finished - res.startTime));
						//				insertTestClass.setString(4, null);
						//				insertTestClass.setString(4, "Stdout:\n" + res.stdout.toString() + "\n\nStderr:\n" + res.stderr.toString());
						insertTestClass.setInt(4, res.nFailures > 0 ? 0 : 1);
						insertTestClass.setInt(5, res.nMethods);
						insertTestClass.setLong(6, res.startTime);
						insertTestClass.setLong(7, res.finished);
						insertTestClass.setInt(8, res.nFailures);
						insertTestClass.setInt(9, res.nSkips);
						insertTestClass.addBatch();
						//					System.out.println("Insert test " + res.name);
					}
					insertTestClass.executeBatch();
					ResultSet rs = insertTestClass.getGeneratedKeys();
					for (TestResult res : working) {
						rs.next();
						int testClassID = rs.getInt(1);
//						System.out.println("Log>>" + res.name+"#"+testClassID);
						testToIdLogger.write(Base64.toBase64(res.name)+"#"+testClassID+"\n");
						if (res.methods != null && res.methods.size() > 0) {
							for (TestResult ts : res.methods) {
//								System.out.println("Insert method on " + testClassID);
//								INSERT INTO test_result_test_method (test_result_test_id,name,errored,failed,duration) VALUES (?,?,?,?,?)
								insertOneMethod.setInt(1, testClassID);
								insertOneMethod.setString(2, (ts.name.length() > 255 ? ts.name.substring(0,255) : ts.name));
								insertOneMethod.setInt(3, (ts.failed ? 1 : (ts.skipped ? 2 : 0)));
								insertOneMethod.setInt(4, (int) (ts.endTime - ts.startTime));
								if(ts.exception == null)
									insertOneMethod.setNull(5, Types.VARCHAR);
								else
									insertOneMethod.setString(5, (ts.exception.length() > 255 ? ts.exception.substring(0, 255) : ts.exception));
								insertOneMethod.addBatch();
							}
						}
					}
					rs.close();
					insertOneMethod.executeBatch();
					rs = insertOneMethod.getGeneratedKeys();
					for(TestResult res : working)
					{
						if(res.methods != null)
							for(TestResult ts : res.methods)
							{
								rs.next();
								int testMethodId = rs.getInt(1);
								testToIdLogger.write(Base64.toBase64(res.name)+"#"+Base64.toBase64(ts.name)+"#"+testMethodId+"\n");
								testMethodIDs.put(res.name + "#" + ts.name, testMethodId);
							}
					}
//					System.out.println("Inserted");
//					for(TestResult res : working)
//						System.out.println("\t>"+res.name);
					working.clear();
					synchronized (insertQueue) {
						insertSenderWorking = false;
						insertQueue.notify();
					}
				} catch (Throwable ex) {
					ex.printStackTrace();
				}
			}
		}
	});

	static URLClassLoader ldr;

	static Connection getConnection() {
		try {
//			System.out.println("Connecting to mysql");
			URL u = new URL("jar:file:/repo/lib/mysql-connector-java-5.0.8-bin.jar!/");
			String classname = "com.mysql.jdbc.Driver";
			ldr = new URLClassLoader(new URL[] { u });
			Driver d = (Driver) Class.forName(classname, true, ldr).newInstance();
			DriverManager.registerDriver(new SQLDriverHack(d));
			Connection ret = DriverManager
					.getConnection("jdbc:mysql://diffcov2017.c5smcgnslo73.us-east-1.rds.amazonaws.com/diffcov?user=diffcov&password=sqFycTgL35H5yegbe&useServerPrepStmts=false&rewriteBatchedStatements=true");
			return ret;
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return null;
	}

	private void clearTest() {
		methodStart = 0;
		methodErrored = false;
		methodFailed = false;
		className = null;
		stdout = new StringBuffer();
		stderr = new StringBuffer();
		nMethods = 0;
		startTime = 0;
		finished = 0;
		failed = false;
		methodName = null;
		nErrors = 0;
		nFailures = 0;
		msg = new TestEventMessage();
		msg.runUID = uuid;
	}


	static MySQLLogger instance;

	LinkedList<TestEventMessage> queueToSend = new LinkedList<TestEventMessage>();
	boolean senderWorking = false;

	private void init() {

	}

	public static boolean LOG_PER_METHOD = false;
	public String uuid;

	boolean inserterStarted;
	protected boolean insertSenderWorking;

	public void init(String projectName, String notes, String uuid) {
		//		System.err.println("Initializing neo4j");
		this.uuid = uuid;
		msg = new TestEventMessage();
		msg.notes = notes;
		msg.runUID = uuid;
		msg.testClass = projectName;
		msg.started = System.currentTimeMillis();
		msg.type = MessageType.CREATE_RUN;
		sendMessage(msg);
		if (!inserterStarted) {
			inserter.setDaemon(true);
			inserter.start();
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

				@Override
				public void run() {

					//Make sure the queue is caught up in case we die after this
					System.out.println("Waiting for sender to finish");
					synchronized (insertQueue) {
						while (!insertQueue.isEmpty() || insertSenderWorking)
							try {
								insertQueue.wait();
							} catch (InterruptedException e) {
							}
					}
					try {
						if(db != null)
							db.close();
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					try {
						testToIdLogger.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
//					System.out.println("Finished logging");

				}
			}));
			inserterStarted = true;
		}
	}

	static{
		instance = new MySQLLogger();
		instance.init();
	}
	public static MySQLLogger instance() {
		return instance;
	}

	public void methodError() {
		this.methodErrored = true;
		nErrors++;
	}

	public void methodFailure() {
		this.methodFailed = true;
		nFailures++;
	}

	public void startTestClass(String className) {
		clearTest();
		this.className = className;
		msg.started = System.currentTimeMillis();
		msg.testClass = className;
		msg.type = MessageType.CREATE_TEST;
		//		System.out.println("Start test class: " + msg.testClass);
		sendMessage(msg);
	}

	String lastEndedTestClass = null;

	public void endTestClass() {
		msg.ended = System.currentTimeMillis();
		msg.nMethods = nMethods;
		msg.nFailures = nFailures;
		msg.nErrors = nErrors;
		msg.type = MessageType.END_TEST;
		lastEndedTestClass = msg.testClass;
		//		System.out.println("Neo4J Ending test class");
		//		new Exception().printStackTrace();
		sendMessage(msg);
	}

	public void startTest(String methodName) {
		msg.started = System.currentTimeMillis();
		msg.testMethod = methodName;
		msg.type = MessageType.CREATE_METHOD;
		sendMessage(msg);
		this.methodName = methodName;
		this.methodErrored = false;
		this.methodFailed = false;
		nMethods++;
	}

	TestEventMessage msg;

	public void endTest() {
		//		System.out.println("NEO4J end test start log per method " + LOG_PER_METHOD);
		msg.ended = System.currentTimeMillis();
		msg.nFailures = methodFailed ? 1 : 0;
		msg.nErrors = methodErrored ? 1 : 0;
		msg.type = MessageType.END_METHOD;
		sendMessage(msg);
		//		System.out.println("NEO4J end test finish");
	}

	private void sendMessage(TestEventMessage msg) {
		//		System.out.println(msg.type + " "  + msg.testClass);
		synchronized (queueToSend) {
			queueToSend.add(msg);
			this.msg = new TestEventMessage();
			this.msg.testClass = msg.testClass;
			this.msg.testMethod = msg.testMethod;
			this.msg.started = msg.started;
			this.msg.ended = msg.ended;
			this.msg.nFailures = msg.nFailures;
			this.msg.nErrors = msg.nErrors;
			this.msg.runUID = msg.runUID;
			this.msg.type = msg.type;
			queueToSend.notify();
		}
	}

	public static class TestResult {
		public transient long endTime;
		public transient StringBuffer stdout = new StringBuffer();
		public transient StringBuffer stderr = new StringBuffer();
		public int nMethods = 0;
		public transient long startTime = System.currentTimeMillis();
		public transient long finished;
		public boolean failed;
		public int nFailures;
		public int nSkips;
		public String name;
		public transient boolean reported;
		public transient LinkedList<TestResult> methods;
		public String exception;
		public boolean skipped;
		
		public TestResult()
		{
			
		}
		public TestResult(String name) {
			if (null == name || name.equals("null")) {
				new Exception().printStackTrace();
			}
			this.name = name;
		}
	}
	public static class TestEventMessage {
		public static enum MessageType {
			CREATE_RUN, CREATE_TEST, END_TEST, CREATE_METHOD, END_METHOD
		}

		public MessageType type;
		public long started;
		public long ended;
		public String notes;
		public String runUID;
		public String testClass;
		public String testMethod;
		public int nMethods;
		public int nFailures;
		public int nErrors;
	}
}
