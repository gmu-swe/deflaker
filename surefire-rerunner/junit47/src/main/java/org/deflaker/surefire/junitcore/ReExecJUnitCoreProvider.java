package org.deflaker.surefire.junitcore;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.plugins.surefire.report.ReportTestCase;
import org.apache.maven.plugins.surefire.report.ReportTestSuite;
import org.apache.maven.reporting.MavenReportException;
import org.apache.maven.surefire.booter.Command;
import org.apache.maven.surefire.booter.CommandListener;
import org.apache.maven.surefire.booter.CommandReader;
import org.apache.maven.surefire.common.junit4.JUnit4RunListener;
import org.apache.maven.surefire.common.junit4.JUnitTestFailureListener;
import org.apache.maven.surefire.common.junit4.Notifier;
import org.apache.maven.surefire.common.junit48.FilterFactory;
import org.apache.maven.surefire.common.junit48.JUnit48Reflector;
import org.apache.maven.surefire.common.junit48.JUnit48TestChecker;
import org.apache.maven.surefire.junitcore.ConcurrentRunListener;
import org.apache.maven.surefire.junitcore.JUnitCoreParameters;
import org.apache.maven.surefire.junitcore.JUnitCoreRunListener;
import org.apache.maven.surefire.junitcore.JUnitCoreWrapper;
import org.apache.maven.surefire.junitcore.NonConcurrentRunListener;
import org.apache.maven.surefire.junitcore.TestSet;
import org.apache.maven.surefire.providerapi.AbstractProvider;
import org.apache.maven.surefire.providerapi.ProviderParameters;
import org.apache.maven.surefire.report.ConsoleLogger;
import org.apache.maven.surefire.report.ReporterFactory;
import org.apache.maven.surefire.suite.RunResult;
import org.apache.maven.surefire.testset.TestListResolver;
import org.apache.maven.surefire.testset.TestSetFailedException;
import org.apache.maven.surefire.util.RunOrderCalculator;
import org.apache.maven.surefire.util.ScanResult;
import org.apache.maven.surefire.util.ScannerFilter;
import org.apache.maven.surefire.util.TestsToRun;
import org.deflaker.runtime.Base64;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.unmodifiableCollection;
import static org.apache.maven.surefire.booter.CommandReader.getReader;
import static org.apache.maven.surefire.common.junit4.JUnit4ProviderUtil.generateFailingTests;
import static org.apache.maven.surefire.common.junit4.JUnit4RunListenerFactory.createCustomListeners;
import static org.apache.maven.surefire.common.junit4.Notifier.pureNotifier;
import static org.apache.maven.surefire.junitcore.ConcurrentRunListener.createInstance;
import static org.apache.maven.surefire.report.ConsoleOutputCapture.startCapture;
import static org.apache.maven.surefire.testset.TestListResolver.optionallyWildcardFilter;
import static org.apache.maven.surefire.util.TestsToRun.fromClass;

/**
 * Based heavily on surefire-junit47 "JunitCoreProvider.java", original @author Kristian Rosenvold
 * licensed under ASF 2.0 (license header above)
 * 
 * Modified by Jon Bell
 */

@SuppressWarnings( { "UnusedDeclaration" } )
public class ReExecJUnitCoreProvider
    extends AbstractProvider
{
    private final ClassLoader testClassLoader;

    private final JUnitCoreParameters jUnitCoreParameters;

    private final ScannerFilter scannerFilter;

    private final Collection<RunListener> customRunListeners;

    private final ProviderParameters providerParameters;

    private final ScanResult scanResult;

    private final int rerunFailingTestsCount;

    private final JUnit48Reflector jUnit48Reflector;

    private final RunOrderCalculator runOrderCalculator;

    private TestListResolver testResolver;

    private final CommandReader commandsReader;

    private TestsToRun testsToRun;

    private final int rerunSeparateJVMCount;

    private final String builddir;

    private final File reportsDir;

    public ReExecJUnitCoreProvider( ProviderParameters bootParams )
    {
        // don't start a thread in CommandReader while we are in in-plugin process
        commandsReader = bootParams.isInsideFork() ? getReader().setShutdown( bootParams.getShutdown() ) : null;
        providerParameters = bootParams;
        testClassLoader = bootParams.getTestClassLoader();
        scanResult = bootParams.getScanResult();
        runOrderCalculator = bootParams.getRunOrderCalculator();
        jUnitCoreParameters = new JUnitCoreParameters( bootParams.getProviderProperties() );
        scannerFilter = new JUnit48TestChecker( testClassLoader );
        testResolver = bootParams.getTestRequest().getTestListResolver();
        rerunFailingTestsCount = bootParams.getTestRequest().getRerunFailingTestsCount();
        String listeners = bootParams.getProviderProperties().get( "listener" );
        customRunListeners = unmodifiableCollection( createCustomListeners( listeners ) );
        jUnit48Reflector = new JUnit48Reflector( testClassLoader );
        builddir = bootParams.getProviderProperties().get("builddir");
        rerunSeparateJVMCount = (bootParams.getProviderProperties().get("rerunCount") != null ? Integer.valueOf(bootParams.getProviderProperties().get("rerunCount")) : 0);
        reportsDir = bootParams.getReporterConfiguration().getReportsDirectory();

    }

    private HashSet<String> getPassedRerunMethods(String buildDir) {
		HashSet<String> passedTests = new HashSet<String>();
		File f = new File(reportsDir, "rerunResults");
		if (f.exists()) {
			try {
				Scanner s = new Scanner(f);
				while (s.hasNextLine()) {
					String[] d = s.nextLine().split("\t");
					if ("OK".equals(d[1]))
						passedTests.add(Base64.fromBase64(d[0]));
				}
				s.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return passedTests;
    }
    
    private TestsToRun getFailedTests(String buildDir) {
    	HashSet<Class<?>> failedTestClasses = new HashSet<Class<?>>();
    	FlakySurefireReportParser parser = new FlakySurefireReportParser(Collections.singletonList(new File(reportsDir.getAbsolutePath().replace("-isolated-reruns", ""))), Locale.US);
    	try {
    		File lst = new File(builddir+"/diffcov-tests-rerun");
    		if(lst.exists())
    			lst.delete();
        	FileWriter failedTestWriter = new FileWriter(lst);

			List<ReportTestSuite> tests = parser.parseXMLReportFiles();
			for(ReportTestSuite t : tests)
			{
				if(t.getNumberOfErrors() > 0 || t.getNumberOfFailures() > 0 || t.getNumberOfFlakes() > 0)
				{
					String cn = t.getFullClassName();
					if(cn.startsWith("deflaker."))
						continue;
					failedTestClasses.add(testClassLoader.loadClass(t.getFullClassName()));
					for(ReportTestCase c : t.getTestCases())
					{
						if(c.hasFailure() || c.getFailureType() != null)
						{
							failedTestWriter.write(cn+"#"+c.getName()+"\n");
						}
					}
				}
			}
			failedTestWriter.close();
		} catch (MavenReportException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	
		return new TestsToRun(failedTestClasses);
	}
    
    public Iterable<Class<?>> getSuites()
    {
    	if(rerunSeparateJVMCount > 0)
    	{
    		//Figure out which tests specifically to run based on failures.
			LinkedList<Class<?>> theTests = new LinkedList<Class<?>>();
			for (Class<?> c : getFailedTests(builddir)) {
				for (int i = 0; i < rerunSeparateJVMCount; i++)
					theTests.add(c);
			}
			return theTests;
    	}
    	else
    		testsToRun = scanClassPath();
        return testsToRun;
    }

    private boolean isSingleThreaded()
    {
        return jUnitCoreParameters.isNoThreading();
    }

    public RunResult invoke( Object forkTestSet )
        throws TestSetFailedException
    {
    	if(rerunSeparateJVMCount > 0)
    	{
    		//In a fork.
    		File f = new File(builddir+"/diffcov-tests-rerun");
    		if(f.exists())
    		{
    			Scanner s = null;
				try {
					s = new Scanner(f);
					ArrayList<String> tests = new ArrayList<String>();
					HashSet<String> passedTests = getPassedRerunMethods(builddir);
					while(s.hasNextLine())
					{
						String t = s.nextLine();
						if(!passedTests.contains(t))
							tests.add(t);
					}
					testResolver = new TestListResolver(tests);
					if(tests.isEmpty())
					{
						return RunResult.noTestsRun();
					}
				} catch (FileNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				finally{
	    			s.close();
				}
    		}
    	}
    	
        final ReporterFactory reporterFactory = providerParameters.getReporterFactory();

        final RunResult runResult;

        final ConsoleLogger consoleLogger = providerParameters.getConsoleLogger();

        Filter filter = jUnit48Reflector.isJUnit48Available() ? createJUnit48Filter() : null;

        Notifier notifier =
            new Notifier( createRunListener( reporterFactory, consoleLogger ), getSkipAfterFailureCount() );
        // startCapture() called in createRunListener() in prior to setTestsToRun()

        if ( testsToRun == null )
        {
            setTestsToRun( forkTestSet );
        }

        // Add test failure listener
        JUnitTestFailureListener testFailureListener = new JUnitTestFailureListener();
        notifier.addListener( testFailureListener );

        if ( isFailFast() && commandsReader != null )
        {
            registerPleaseStopJUnitListener( notifier );
        }

        try
        {
            JUnitCoreWrapper core = new JUnitCoreWrapper( notifier, jUnitCoreParameters, consoleLogger );

            if ( commandsReader != null )
            {
                registerShutdownListener( testsToRun );
                commandsReader.awaitStarted();
            }

            notifier.asFailFast( isFailFast() );
            core.execute( testsToRun, customRunListeners, filter );
            notifier.asFailFast( false );

            // Rerun failing tests if rerunFailingTestsCount is larger than 0
            if ( isRerunFailingTests() )
            {
                Notifier rerunNotifier = pureNotifier();
                notifier.copyListenersTo( rerunNotifier );
                JUnitCoreWrapper rerunCore = new JUnitCoreWrapper( rerunNotifier, jUnitCoreParameters, consoleLogger );
                for ( int i = 0; i < rerunFailingTestsCount && !testFailureListener.getAllFailures().isEmpty(); i++ )
                {
                    List<Failure> failures = testFailureListener.getAllFailures();
                    Map<Class<?>, Set<String>> failingTests = generateFailingTests( failures, testClassLoader );
                    testFailureListener.reset();
                    FilterFactory filterFactory = new FilterFactory( testClassLoader );
                    Filter failingMethodsFilter = filterFactory.createFailingMethodFilter( failingTests );
                    rerunCore.execute( testsToRun, failingMethodsFilter );
                }
            }
        }
        finally
        {
            runResult = reporterFactory.close();
            notifier.removeListeners();
        }
        return runResult;
    }

    private void setTestsToRun( Object forkTestSet )
        throws TestSetFailedException
    {
        if ( forkTestSet instanceof TestsToRun )
        {
            testsToRun = (TestsToRun) forkTestSet;
        }
        else if ( forkTestSet instanceof Class )
        {
            Class<?> theClass = (Class<?>) forkTestSet;
            testsToRun = fromClass( theClass );
        }
        else
        {
            testsToRun = scanClassPath();
        }
    }

    private boolean isRerunFailingTests()
    {
        return rerunFailingTestsCount > 0;
    }

    private boolean isFailFast()
    {
        return providerParameters.getSkipAfterFailureCount() > 0;
    }

    private int getSkipAfterFailureCount()
    {
        return isFailFast() ? providerParameters.getSkipAfterFailureCount() : 0;
    }

    private void registerShutdownListener( final TestsToRun testsToRun )
    {
        commandsReader.addShutdownListener( new CommandListener()
        {
            public void update( Command command )
            {
                testsToRun.markTestSetFinished();
            }
        } );
    }

    private void registerPleaseStopJUnitListener( final Notifier stoppable )
    {
        commandsReader.addSkipNextTestsListener( new CommandListener()
        {
            public void update( Command command )
            {
                stoppable.pleaseStop();
            }
        } );
    }

    private JUnit4RunListener createRunListener( ReporterFactory reporterFactory, ConsoleLogger consoleLogger )
        throws TestSetFailedException
    {
        if ( isSingleThreaded() )
        {
            NonConcurrentRunListener rm = new NonConcurrentRunListener( reporterFactory.createReporter() );
            startCapture( rm );
            return rm;
        }
        else
        {
            final Map<String, TestSet> testSetMap = new ConcurrentHashMap<String, TestSet>();

            ConcurrentRunListener listener = createInstance( testSetMap, reporterFactory, isParallelTypes(),
                                                             isParallelMethodsAndTypes(), consoleLogger );
            startCapture( listener );

            return new JUnitCoreRunListener( listener, testSetMap );
        }
    }

    private boolean isParallelMethodsAndTypes()
    {
        return jUnitCoreParameters.isParallelMethods() && isParallelTypes();
    }

    private boolean isParallelTypes()
    {
        return jUnitCoreParameters.isParallelClasses() || jUnitCoreParameters.isParallelSuites();
    }

    private Filter createJUnit48Filter()
    {
        final FilterFactory factory = new FilterFactory( testClassLoader );
        Map<String, String> props = providerParameters.getProviderProperties();
        Filter groupFilter = factory.canCreateGroupFilter( props ) ? factory.createGroupFilter( props ) : null;
        TestListResolver methodFilter = optionallyWildcardFilter( testResolver );
        boolean onlyGroups = methodFilter.isEmpty() || methodFilter.isWildcard();
        if ( onlyGroups )
        {
            return groupFilter;
        }
        else
        {
            Filter jUnitMethodFilter = factory.createMethodFilter( methodFilter );
            return groupFilter == null ? jUnitMethodFilter : factory.and( groupFilter, jUnitMethodFilter );
        }
    }

    private TestsToRun scanClassPath()
    {
        TestsToRun scanned = scanResult.applyFilter( scannerFilter, testClassLoader );
        return runOrderCalculator.orderTestClasses( scanned );
    }
}
