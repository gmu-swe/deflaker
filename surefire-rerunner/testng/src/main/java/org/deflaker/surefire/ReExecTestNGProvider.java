package org.deflaker.surefire;

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
import org.apache.maven.plugins.surefire.report.SurefireReportParser;
import org.apache.maven.reporting.MavenReportException;
import org.apache.maven.surefire.booter.Command;
import org.apache.maven.surefire.booter.CommandListener;
import org.apache.maven.surefire.booter.CommandReader;
import org.apache.maven.surefire.cli.CommandLineOption;
import org.apache.maven.surefire.providerapi.AbstractProvider;
import org.apache.maven.surefire.providerapi.ProviderParameters;
import org.apache.maven.surefire.report.ConsoleOutputReceiver;
import org.apache.maven.surefire.report.ReporterConfiguration;
import org.apache.maven.surefire.report.ReporterFactory;
import org.apache.maven.surefire.report.RunListener;
import org.apache.maven.surefire.suite.RunResult;
import org.apache.maven.surefire.testng.TestNGDirectoryTestSuite;
import org.apache.maven.surefire.testng.TestNGXmlTestSuite;
import org.apache.maven.surefire.testng.utils.FailFastEventsSingleton;
import org.apache.maven.surefire.testset.TestListResolver;
import org.apache.maven.surefire.testset.TestRequest;
import org.apache.maven.surefire.testset.TestSetFailedException;
import org.apache.maven.surefire.util.RunOrderCalculator;
import org.apache.maven.surefire.util.ScanResult;
import org.apache.maven.surefire.util.TestsToRun;
import org.deflaker.runtime.Base64;

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

import static org.apache.maven.surefire.booter.CommandReader.getReader;
import static org.apache.maven.surefire.report.ConsoleOutputCapture.startCapture;
import static org.apache.maven.surefire.testset.TestListResolver.getEmptyTestListResolver;
import static org.apache.maven.surefire.testset.TestListResolver.optionallyWildcardFilter;
import static org.apache.maven.surefire.util.TestsToRun.fromClass;

/**
 * Based heavily on surefire-junit4 "Junit4Runner.java", original @author Kristian Rosenvold
 * licensed under ASF 2.0 (license header above)
 * 
 * Modified by Jon Bell
 * @noinspection UnusedDeclaration
 */
public class ReExecTestNGProvider
    extends AbstractProvider
{
    private final Map<String, String> providerProperties;

    private final ReporterConfiguration reporterConfiguration;

    private final ClassLoader testClassLoader;

    private final ScanResult scanResult;

    private final TestRequest testRequest;

    private final ProviderParameters providerParameters;

    private final RunOrderCalculator runOrderCalculator;

    private final List<CommandLineOption> mainCliOptions;

    private final CommandReader commandsReader;

    private TestsToRun testsToRun;
    
    private final String builddir;

    private final int rerunSeparateJVMCount;

    private final File reportsDir;

    public ReExecTestNGProvider( ProviderParameters bootParams )
    {
        // don't start a thread in CommandReader while we are in in-plugin process
        commandsReader = bootParams.isInsideFork() ? getReader().setShutdown( bootParams.getShutdown() ) : null;
        providerParameters = bootParams;
        testClassLoader = bootParams.getTestClassLoader();
        runOrderCalculator = bootParams.getRunOrderCalculator();
        providerProperties = bootParams.getProviderProperties();
        testRequest = bootParams.getTestRequest();
        reporterConfiguration = bootParams.getReporterConfiguration();
        scanResult = bootParams.getScanResult();
        mainCliOptions = bootParams.getMainCliOptions();
        builddir = bootParams.getProviderProperties().get("builddir");
        rerunSeparateJVMCount = (bootParams.getProviderProperties().get("rerunCount") != null ? Integer.valueOf(bootParams.getProviderProperties().get("rerunCount")) : 0);
        reportsDir = bootParams.getReporterConfiguration().getReportsDirectory();

    }

    private TestListResolver testResolver;
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
        if ( isFailFast() && commandsReader != null )
        {
            registerPleaseStopListener();
        }

        final ReporterFactory reporterFactory = providerParameters.getReporterFactory();
        final RunListener reporter = reporterFactory.createReporter();
        /**
         * {@link org.apache.maven.surefire.report.ConsoleOutputCapture#startCapture(ConsoleOutputReceiver)}
         * called in prior to initializing variable {@link #testsToRun}
         */
        startCapture( (ConsoleOutputReceiver) reporter );

        RunResult runResult;
        try
        {
            if ( isTestNGXmlTestSuite( testRequest ) )
            {
                if ( commandsReader != null )
                {
                    commandsReader.awaitStarted();
                }
                TestNGXmlTestSuite testNGXmlTestSuite = newXmlSuite();
                testNGXmlTestSuite.locateTestSets();
                testNGXmlTestSuite.execute( reporter );
            }
            else
            {
                if ( testsToRun == null )
                {
                    if ( forkTestSet instanceof TestsToRun )
                    {
                        testsToRun = (TestsToRun) forkTestSet;
                    }
                    else if ( forkTestSet instanceof Class )
                    {
                        testsToRun = fromClass( (Class<?>) forkTestSet );
                    }
                    else
                    {
                        testsToRun = scanClassPath();
                    }
                }

                if ( commandsReader != null )
                {
                    registerShutdownListener( testsToRun );
                    commandsReader.awaitStarted();
                }
                TestNGDirectoryTestSuite suite = newDirectorySuite();
                suite.execute( testsToRun, reporter );
            }
        }
        finally
        {
            runResult = reporterFactory.close();
        }
        return runResult;
    }

    boolean isTestNGXmlTestSuite( TestRequest testSuiteDefinition )
    {
        Collection<File> suiteXmlFiles = testSuiteDefinition.getSuiteXmlFiles();
        return !suiteXmlFiles.isEmpty() && !hasSpecificTests();
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

    private void registerPleaseStopListener()
    {
        commandsReader.addSkipNextTestsListener( new CommandListener()
        {
            public void update( Command command )
            {
                FailFastEventsSingleton.getInstance().setSkipOnNextTest();
            }
        } );
    }

    private TestNGDirectoryTestSuite newDirectorySuite()
    {
        return new TestNGDirectoryTestSuite( testRequest.getTestSourceDirectory().toString(), providerProperties,
                                             reporterConfiguration.getReportsDirectory(), getTestFilter(),
                                             mainCliOptions, getSkipAfterFailureCount() );
    }

    private TestNGXmlTestSuite newXmlSuite()
    {
        return new TestNGXmlTestSuite( testRequest.getSuiteXmlFiles(),
                                       testRequest.getTestSourceDirectory().toString(),
                                       providerProperties,
                                       reporterConfiguration.getReportsDirectory(), getSkipAfterFailureCount() );
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
        if ( isTestNGXmlTestSuite( testRequest ) )
        {
            return Collections.emptySet();
        }
        else
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
    }

    private TestsToRun scanClassPath()
    {
        final TestsToRun scanned = scanResult.applyFilter( null, testClassLoader );
        return runOrderCalculator.orderTestClasses( scanned );
    }

    private boolean hasSpecificTests()
    {
        TestListResolver specificTestPatterns = testRequest.getTestListResolver();
        return !specificTestPatterns.isEmpty() && !specificTestPatterns.isWildcard();
    }

    private TestListResolver getTestFilter()
    {
        TestListResolver filter = optionallyWildcardFilter( ( testResolver == null ? testRequest.getTestListResolver() : testResolver ) );
        return filter.isWildcard() ? getEmptyTestListResolver() : filter;
    }
}
