package org.deflaker.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.surefire.report.ReportTestCase;
import org.apache.maven.plugins.surefire.report.ReportTestSuite;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.MavenReportException;
import org.deflaker.diff.ClassInfo;
import org.deflaker.diff.Edit;
import org.deflaker.runtime.TestLineHit;

@Mojo(name = "report", defaultPhase = LifecyclePhase.VERIFY)
public class DeflakerReportingMojo extends AbstractMojo {
	@Component
	private MavenProject project;
	@Parameter(readonly = true, required = true, property = "debugCovFile")
	private String covFile;
	
	@Parameter(readonly = true, required= true)
	private String reportedTestsFile;

	@Parameter(readonly = true, required = true, property = "maven.test.failure.ignore", defaultValue = "true")
	private boolean testFailureIgnore;
	
	@Parameter(readonly = true, required = true, property = "debugGitDir")
	private String gitDir;
	@Parameter(readonly = true, required = true, property = "debugDiffFile")
	private String diffFile;
	
	@Parameter(readonly = true, required = false, defaultValue="false")
	private boolean isFailsafe;
	
	Log consoleLogger;
	private void logInfo(String str)
	{
		consoleLogger.info(str);
		if(fw != null)
			fw.println("[INFO] " + str);
	}
	private void logWarn(String str)
	{
		consoleLogger.warn(str);
		if(fw != null)
			fw.println("[WARN] " + str);
	}
	
	HashSet<String> knownFlakes;
	PrintWriter fw;
	PreparedStatement insertCoverage;
	PreparedStatement getCoverageClass;
	PreparedStatement insertCoverageClass;
	PreparedStatement getCoverageMethod;
	PreparedStatement insertCoverageMethod;

	
	private HashMap<String, Integer> cachedClasses = new HashMap<String, Integer>();
	private int getCovClassId(String clazz) throws SQLException
	{
		clazz = clazz.replace('/', '.');
		if (!cachedClasses.containsKey(clazz)) {
			getCoverageClass.setString(1, clazz);
			getCoverageClass.executeQuery();
			ResultSet rs = getCoverageClass.getResultSet();
			if (rs.next()) {
				int r = rs.getInt(1);
				cachedClasses.put(clazz, r);
				rs.close();
			} else {
				rs.close();
				insertCoverageClass.setString(1, clazz);
				insertCoverageClass.executeUpdate();
				rs = insertCoverageClass.getGeneratedKeys();
				rs.next();
				int r = rs.getInt(1);
				rs.close();
				cachedClasses.put(clazz, r);
			}
		}
		return cachedClasses.get(clazz);
	}
	
	private HashMap<String, Integer> cachedMethods = new HashMap<String, Integer>();
	private int getCovMethodId(String meth) throws SQLException
	{
		int idx = meth.lastIndexOf('.');
		if(idx > 0)
			meth = meth.substring(idx+1);
		meth = meth.replace('/', '.');
		if (!cachedMethods.containsKey(meth)) {
			getCoverageMethod.setString(1, meth);
			getCoverageMethod.executeQuery();
			ResultSet rs = getCoverageMethod.getResultSet();
			if (rs.next()) {
				int r = rs.getInt(1);
				cachedMethods.put(meth, r);
				rs.close();
			} else {
				rs.close();
				insertCoverageMethod.setString(1, meth);
				insertCoverageMethod.executeUpdate();
				rs = insertCoverageMethod.getGeneratedKeys();
				rs.next();
				int r = rs.getInt(1);
				rs.close();
				cachedMethods.put(meth, r);
			}
		}
		return cachedMethods.get(meth);
	}

	public final static boolean ALL_COVERAGE = Boolean.valueOf(System.getProperty("diffcov.allCoverage","false"));
	public final static boolean LAZY_COV = System.getProperty("diffcov.lazycov") != null;
	
	private static class TestResult {
		int nPassedOriginalJVM;
		int nFailedOriginalJVM;
		int nFailedFreshJVM;
		int nPassedFreshJVM;
	}
		
	@SuppressWarnings("unchecked")
	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		Connection db = null;
		FirebaseLogger firebase = null;
		if(System.getenv("TRAVIS") != null)
		{
			//Save to firebase
			if(DeflakerDiffingMojo.firebase == null)
				DeflakerDiffingMojo.firebase = new FirebaseLogger();
			firebase = DeflakerDiffingMojo.firebase;
			System.out.println("Reporter using firebase");
		}
		List<ReportTestSuite> tests = null;

		try{
			consoleLogger = getLog();
			boolean toMysql = false;
			PreparedStatement insertDiffData = null;
			HashMap<String, Integer> testClassToId = null;
			
			int uncoveredClasses = 0;
			int uncoveredLines = 0;
			int uncoveredMethods = 0;
			if(System.getProperty("diffCov.report") != null)
			{
				try {
					fw = new PrintWriter(new FileWriter(System.getProperty("diffCov.report")));
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			logInfo("------------------------------------------------------------------------");
			logInfo("TEST DIFFCOV ANALYSIS");
			logInfo(project.getName()+project.getArtifactId());
			logInfo("------------------------------------------------------------------------");
			
			if(System.getProperty("diffCov.knownFlakes") != null)
			{
				knownFlakes = new HashSet<String>();
				for(String s : System.getProperty("diffCov.knownFlakes").split(","))
					knownFlakes.add(s);
				logInfo("Using list of known flakes: " + knownFlakes);
			}
			
			HashMap<String, ClassInfo> diffs;
			HashMap<String, LinkedList<TestLineHit>> covByDiffedClass = new HashMap<String, LinkedList<TestLineHit>>();
			HashMap<String, LinkedList<TestLineHit>> covByTestClass = new HashMap<String, LinkedList<TestLineHit>>();
			HashMap<String, LinkedList<TestLineHit>> backupCovByTestClass = new HashMap<String, LinkedList<TestLineHit>>();
			HashSet<String> poisonDiffs = new HashSet<String>();
			


			consoleLogger.info("Using covFile: " + covFile);
			consoleLogger.info("Using difFile: " + diffFile);
			int testExecId = -1;
			try {
				if(!new File(diffFile).exists() || !new File(covFile).exists())
				{
					logInfo("No test data found");
					return;
				}
				if(System.getProperty("diffcov.mysql") != null)
				{
					testExecId = Integer.valueOf(System.getProperty("diffcov.studyid"));
					toMysql = true;
					testClassToId = new HashMap<String, Integer>();
					if (new File(covFile + ".testToId").exists()) {
						Scanner s = new Scanner(new File(covFile + ".testToId"));
						while (s.hasNextLine()) {
							String[] d = s.nextLine().split("#");
							try {
								if (d.length == 2)
									testClassToId.put(Base64.fromBase64(d[0]), Integer.valueOf(d[1]));
								else {
									testClassToId.put(Base64.fromBase64(d[0]) + "#" + Base64.fromBase64(d[1]), Integer.valueOf(d[2]));
								}
							} catch (Throwable t) {								
								System.out.println("Bad line>>> "+Arrays.toString(d));
								t.printStackTrace();
							}
						}
						s.close();
					}
					String classname = "com.mysql.jdbc.Driver";
					try {
						Driver d = (Driver) Class.forName(classname).newInstance();
						DriverManager.registerDriver(d);
						db = DriverManager.getConnection("jdbc:mysql://diffcov2017.c5smcgnslo73.us-east-1.rds.amazonaws.com/diffcov?user=diffcov&password=sqFycTgL35H5yegbe&useServerPrepStmts=false&rewriteBatchedStatements=true");
						getCoverageClass = db.prepareStatement("SELECT id FROM java_class WHERE name=?");
						insertCoverageClass = db.prepareStatement("INSERT INTO java_class (name) VALUES (?)", PreparedStatement.RETURN_GENERATED_KEYS);
						getCoverageMethod = db.prepareStatement("SELECT id FROM java_method WHERE name=?");
						insertCoverageMethod = db.prepareStatement("INSERT INTO java_method (name) VALUES (?)", PreparedStatement.RETURN_GENERATED_KEYS);

						insertCoverage = db.prepareStatement("INSERT INTO test_result_coverage (test_method_id,java_class_id,statement, method, is_backup_coverage) VALUES (?,?,?,?,?)");
						insertDiffData = db.prepareStatement("UPDATE test_result_test SET nFlaky=? WHERE id=?");

					} catch (SQLException | InstantiationException | IllegalAccessException ex) {
						ex.printStackTrace();
						throw new MojoFailureException(ex.getMessage());
					}
				}
				FileInputStream fis = new FileInputStream(diffFile);
				ObjectInputStream ois = new ObjectInputStream(fis);
				diffs = (HashMap<String, ClassInfo>) ois.readObject();
				ois.close();
				
				for(String s : diffs.keySet())
					if(diffs.get(s) == null)
						poisonDiffs.add(s);
				
				Scanner covScanner = new Scanner(new File(covFile));
				while(covScanner.hasNextLine())
				{
					try {
						TestLineHit h = TestLineHit.fromString(covScanner.nextLine());
						if (!covByDiffedClass.containsKey(h.probeClass))
							covByDiffedClass.put(h.probeClass, new LinkedList<TestLineHit>());
						covByDiffedClass.get(h.probeClass).add(h);

						if (h.isBackupClassLevel) {
							if (!backupCovByTestClass.containsKey(h.testClass + "." + h.testMethod))
								backupCovByTestClass.put(h.testClass + "." + h.testMethod, new LinkedList<TestLineHit>());
							backupCovByTestClass.get(h.testClass + "." + h.testMethod).add(h);
						} else {
							if (!covByTestClass.containsKey(h.testClass + "." + h.testMethod))
								covByTestClass.put(h.testClass + "." + h.testMethod, new LinkedList<TestLineHit>());
							covByTestClass.get(h.testClass + "." + h.testMethod).add(h);
						}
					} catch (ArrayIndexOutOfBoundsException ex) {
						// File might be corrupt?
					}
				}
				covScanner.close();
				
				if(toMysql && testClassToId != null)
				{
					try {
						//Log all coverage
						boolean doInsert = false;
						insertCoverage.clearBatch();
						for(String diffedClass : covByDiffedClass.keySet())
						{
							int probeId=getCovClassId(diffedClass);
							for(TestLineHit t : covByDiffedClass.get(diffedClass))
							{
								doInsert = true;
								if(testClassToId.get(t.testClass+"#"+t.testMethod) == null)
								{
									System.err.println(">>> Could not find test method " + t.testClass + "#" + t.testMethod);
								} else {
									int tId = testClassToId.get(t.testClass + "#" + t.testMethod);
									insertCoverage.setInt(1, tId);
									insertCoverage.setInt(2, probeId);
									insertCoverage.setInt(3, (t.isClassLevel ? 0 : t.line));
									insertCoverage.setInt(4, (t.methName == null ? 0 : getCovMethodId(t.methName)));
									insertCoverage.setInt(5, (t.isBackupClassLevel ? 1 : 0));

									insertCoverage.addBatch();
								}
							}
						}
						if(doInsert)
							insertCoverage.executeBatch();
					} catch (Throwable e) {
						e.printStackTrace();
					}
				}
				/*
				 * First phase: find diff lines that weren't covered
				 */
				if(!LAZY_COV)
					for(ClassInfo f : diffs.values())
					{
						if(f == null)
							continue;
						if(f.hasStructuralProblems)
						{
							//Only need to find class-level coverage
							if(covByDiffedClass.containsKey(f.className))
								continue;//OK, as long as there's something here, that means the class was hit
							else
							{
								logUntested(f.className,true, null);
								if(firebase != null)
									firebase.logUntestedStructural(f.className);
								uncoveredClasses++;
							}
						}
						else
						{
							HashSet<Integer> linesDiffed = new HashSet<Integer>();
							if(f.codeLinesEdited.isEmpty())
							{
								for(Edit e : f.edits)
								{
									for(int i = e.getEditStart(); i <= e.getEditEnd(); i++)
										linesDiffed.add(i);
								}
							}
							else
								linesDiffed.addAll(f.codeLinesEdited);
							if (covByDiffedClass.containsKey(f.className)) {
								for (TestLineHit h : covByDiffedClass.get(f.className)) {
									linesDiffed.remove(h.line);
								}
							}
							if(!linesDiffed.isEmpty())
							{
								ArrayList<Integer> unCovered = new ArrayList<Integer>(linesDiffed);
								Collections.sort(unCovered);
								logUntested(f.className,false, unCovered.toString());
								if(firebase != null)
									firebase.logUntestedLines(f.className,unCovered);
								uncoveredLines+= linesDiffed.size();
							}
	//						else{
	//							logInfo("All changes have been covered in " + f.className);
	//						}
						}
					}
				for(String s : poisonDiffs)
				{
					logUntested(s, false, " (non-java files are not tracked; keep this in mind if any flaky tests are detected, they may have been impacted by this change!)");
					if(firebase != null)
						firebase.logUntestedStructural(s);

				}
				if(isFailsafe)
					tests = this.getParser("failsafe-reports").parseXMLReportFiles();
				else
					tests = this.getParser("surefire-reports").parseXMLReportFiles();
				filterReportedTests(tests);
				HashMap<String, TestResult> groupedTestCases = new HashMap<String, TestResult>();
				if(tests.size() == 0)
				{
					logWarn("No tests found!");
					return;
				}
				else if(toMysql && (testClassToId == null || testClassToId.size() == 0))
				{
					logWarn("Tests found, but cov data not!");
					return;
				}
				
				// Look for fork rerun results
				File forkResults = new File(project.getBasedir().getAbsolutePath() + "/target/" + (isFailsafe ? "failsafe" : "surefire") + "-reports-isolated-reruns/rerunResults");
				if(forkResults.exists())
				{
					Scanner s = new Scanner(forkResults);
					while(s.hasNextLine())
					{
						String d[] = s.nextLine().split("\t");
						String n = d[0].replace('#', '.');
						TestResult tr = groupedTestCases.get(n);
						if (tr == null) {
							tr = new TestResult();
							groupedTestCases.put(n, tr);
						}
						if("FAILED".equals(d[1]))
							tr.nFailedFreshJVM++;
						else
							tr.nPassedFreshJVM++;

					}
					s.close();
				}

				for(ReportTestSuite s : tests)
				{
					for(ReportTestCase c : s.getTestCases())
					{
						TestResult tr = groupedTestCases.get(c.getFullName());
						if(tr == null)
						{
							tr = new TestResult();
							groupedTestCases.put(c.getFullName(), tr);
						}
						if("flaky".equals(c.getFailureType()))
						{
							tr.nFailedOriginalJVM++;
							tr.nPassedOriginalJVM++;
						}
						else if (c.hasFailure())
							tr.nFailedOriginalJVM++;
						else
							tr.nPassedOriginalJVM++;
					}
				}
				boolean haveFailures = false;

				HashSet<String> reported = new HashSet<String>();
				HashSet<String> reportedFromCov = new HashSet<String>();

				for (ReportTestSuite s : tests) {
					int nFlakes = 0;
					String testKey = s.getFullClassName();

					for (ReportTestCase c : s.getTestCases()) {
						testKey = c.getFullName();
						TestResult tr = groupedTestCases.remove(c.getFullName());
						if(tr == null)
							continue; //when we rerun there will be multiple ReportTestCase objects for each method
						//Found flaky through reruns?
						if(tr.nFailedOriginalJVM == 0) //no failure
							continue;
						if(tr.nPassedFreshJVM > 0 && tr.nPassedOriginalJVM > 0)
						{
							logWarn("FLAKY>> Test " + testKey + " was found to be flaky by rerunning it in both the same and also a fresh JVM! It failed the first time, then eventually passed (both in the same, and in the fresh JVM).");
							if (firebase != null)
								firebase.logFlaky(testKey);
						}
						else if(tr.nPassedFreshJVM > 0)
						{
							logWarn("FLAKY>> Test " + testKey + " was found to be flaky by rerunning it in a fresh JVM! It failed the first time, then eventually passed.");
							if (firebase != null)
								firebase.logFlaky(testKey);

						}
						else if(tr.nPassedOriginalJVM > 0)
						{
							logWarn("FLAKY>> Test " + testKey + " was found to be flaky by rerunning it in the same JVM! It failed the first time, then eventually passed.");
							if (firebase != null)
								firebase.logFlaky(testKey);
						}

						if (isKnownFlaky(testKey) || (c.hasFailure() && !"skipped".equals(c.getFailureType()))) {
//							logInfo("Looking at: " + testKey);
							if (!covByTestClass.containsKey(testKey)) {
								nFlakes++;
								if (backupCovByTestClass.containsKey(testKey)) {
									logFlaky(testKey, backupCovByTestClass.get(testKey));
									if (firebase != null)
										firebase.logFlaky(testKey, backupCovByTestClass.get(testKey));
								} else {
									if(reportedFromCov.add(testKey))
										logFlaky(testKey);
									if (firebase != null)
										firebase.logFlaky(testKey);
								}
							} else {
								// Give the programmer some debugging help
								// maybe?
								StringBuilder sb = new StringBuilder();
								if (!ALL_COVERAGE)
									for (TestLineHit h : covByTestClass.get(testKey)) {
										sb.append('\t');
										sb.append(h.probeClass.replace('/', '.'));
										if (h.isClassLevel) {
											sb.append(" (structural changes)");
										} else {
											sb.append(':');
											sb.append(h.line);
										}
										sb.append('\n');
									}
								if (firebase != null)
									firebase.logNotFlakyFailure(testKey, covByTestClass.get(testKey));
								logFailed(testKey, sb.toString());
							}
							haveFailures = true;
						}
					}
					if(toMysql && nFlakes > 0)
					{
						try {
							if(testClassToId.get(s.getFullClassName()) == null)
							{
								System.err.println("Can't find test class ID>>>" + s.getFullClassName());
							} else {
							insertDiffData.setInt(1, nFlakes);
								// System.out.println(">>"+s.getFullClassName());
								insertDiffData.setInt(2, testClassToId.get(s.getFullClassName()));
								insertDiffData.executeUpdate();
							}
						} catch (SQLException e) {
							e.printStackTrace();
						}
					}
				}
				if(toMysql)
				{
					try {
						PreparedStatement ps = db.prepareStatement("UPDATE test_execution set uncovered_lines=uncovered_lines+?, uncovered_classes=uncovered_classes+?, uncovered_methods=? WHERE id=?");
						ps.setInt(1, uncoveredLines);
						ps.setInt(2, uncoveredClasses);
						ps.setInt(3, uncoveredMethods);
						ps.setInt(4, Integer.valueOf(System.getProperty("diffcov.studyid")));
						ps.executeUpdate();
					} catch (SQLException ex) {
						ex.printStackTrace();
						throw new MojoFailureException("mysql!");
					}
				}
				if(haveFailures)
				{
					if(testFailureIgnore)
						logWarn("\nThere are test failures!\n");
					else
						throw new MojoFailureException("There were test failures!");
				}
			} catch (MavenReportException | IOException | ClassNotFoundException e) {
				e.printStackTrace();
				throw new MojoFailureException(e.getMessage());
			}
		} finally {
			if(tests!= null)
			logReportedTests(tests);
			if(firebase != null)
				firebase.awaitExit();
			if (fw != null)
				fw.close();
			if(db != null)
				try {
					db.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
		}

	}

	private void logReportedTests(List<ReportTestSuite> tests) {
		FileWriter fw = null;
		try {
			fw = new FileWriter(reportedTestsFile, true);
			for (ReportTestSuite s : tests) {
				fw.write(s.getFullClassName());
				fw.write('\n');
			}
		} catch (IOException ex) {

		} finally {
			if (fw != null)
				try {
					fw.close();
				} catch (IOException e) {
				}
		}

	}

	private List<ReportTestSuite> filterReportedTests(List<ReportTestSuite> tests) {
		Scanner s = null;
		try {
			File f = new File(reportedTestsFile);

			if (f.exists()) {
				s = new Scanner(f);
				HashSet<String> testsToFilter = new HashSet<String>();
				List<ReportTestSuite> ret = new LinkedList<ReportTestSuite>();
				while (s.hasNextLine()) {
					testsToFilter.add(s.nextLine());
				}
				for (ReportTestSuite t : tests) {
					if (!testsToFilter.contains(t.getFullClassName()))
						ret.add(t);
				}
				return ret;
			}
		} catch (IOException ex) {

		} finally {
			if (s != null)
				s.close();
		}
		return tests;
	}
	private void logFlaky(String fullClassName, LinkedList<TestLineHit> backupCov) {
		LinkedList<String> l = new LinkedList<String>();
		for(TestLineHit h : backupCov)
			l.add(h.probeClass);
		logWarn("FLAKY>> Test " + fullClassName + " failed, but did not appear to run any changed code. It did touch some changed files though: " + l);		
	}
	private void logFailed(String fullClassName, String debug) {
		logWarn("Test " + fullClassName + " failed, perhaps due to relevant changed code:\n" + debug);
	}

	private void logFlaky(String fullClassName) {
		logWarn("FLAKY>> Test " + fullClassName + " failed, but did not appear to run any changed code");
	}

	private void logUntested(String className, boolean isStructural, String extra) {
		if (isStructural)
			logWarn("Diff has gone untested: " + className + " has structural changes!");
		else
			logWarn("Diff has gone untested: " + className + " has uncovered changes " + extra);
	}

	private boolean isKnownFlaky(String s) {
		if (knownFlakes == null)
			return false;
		return knownFlakes.contains(s);
	}
	private FlakySurefireReportParser parser;

	public FlakySurefireReportParser getParser(String dir) {
		if (parser == null)
			this.parser = new FlakySurefireReportParser(Collections.singletonList(new File(project.getBasedir().getAbsolutePath() + "/target/" + dir)), Locale.US);
		return parser;
	}
}
