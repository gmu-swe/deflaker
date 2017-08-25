package org.deflaker.maven;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.cli.event.ExecutionEventLogger;
import org.apache.maven.execution.AbstractExecutionListener;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.model.Dependency;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;


//@Component(role = ExecutionListener.class, hint = "swe-mysql-logger")
public class LifecycleLogger extends AbstractExecutionListener implements Initializable {
	private ExecutionListener delegate;

	private boolean inserterShouldDie = false;
	private boolean OKToEnd = true;
	private Thread inserter = new Thread(new Runnable() {

		public void run() {
			final Connection db;
			int testID=-1;
			try{
				testID = Integer.valueOf(System.getProperty("diffcov.studyid"));
			}
			catch(Throwable t)
			{
				try{
				Scanner s = new Scanner(new File("/working/vmvm.study.testID"));
				testID = s.nextInt();
				s.close();
				}
				catch(Throwable tt)
				{
					tt.printStackTrace();
				}
			}
			
			try {
				URL u = new URL("jar:file:/repo/lib/mysql-connector-java-5.0.8-bin.jar!/");
				String classname = "com.mysql.jdbc.Driver";
				URLClassLoader ldr = new URLClassLoader(new URL[] { u });
				Driver d = (Driver) Class.forName(classname, true, ldr).newInstance();
				DriverManager.registerDriver(new SQLDriverHack(d));
				db = DriverManager.getConnection("jdbc:mysql://diffcov2017.c5smcgnslo73.us-east-1.rds.amazonaws.com/diffcov?user=diffcov&password=sqFycTgL35H5yegbe&useServerPrepStmts=false&rewriteBatchedStatements=true");
				Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
					
					public void run() {
						try{if(!db.isClosed()) db.close();}catch(SQLException ex){ ex.printStackTrace();}
					}
				}));
			} catch (Exception ex) {
				System.err.println("Couldn't connect to db");
				ex.printStackTrace();
				OKToEnd = true;
				finishedEvents.notify();
				return;
			}
			PreparedStatement ps;
			try {
				ps = db.prepareStatement("INSERT INTO test_execution_module (test_execution_id,starttime,endtime,duration,phase,goal,project,mojo,success,exec_id) VALUES (?,?,?,?,?,?,?,?,?,?)",PreparedStatement.RETURN_GENERATED_KEYS);
			} catch (SQLException ex) {
				ex.printStackTrace();
				OKToEnd = true;
				try {
					db.close();
				} catch (SQLException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				return;
			}
			while (true) {
				HashMap<String, Integer> surefireTestModuleIDs = new HashMap<String, Integer>();
				synchronized (finishedEvents) {
					while (finishedEvents.size() == 0 && !inserterShouldDie) {
						try {
							finishedEvents.wait();

						} catch (InterruptedException ex) {

						}
					}
				}
				MojoExecution ex;
				synchronized (finishedEvents) {
					if (inserterShouldDie && finishedEvents.size() == 0) {
						try {
							db.close();
						} catch (Throwable e) {
							e.printStackTrace();
						}
						OKToEnd = true;
						finishedEvents.notify();
						return;
					}
					ex = finishedEvents.pop();
				}
				if(ex.isSession)
				{
					try {
						PreparedStatement eps = db.prepareStatement("UPDATE test_execution SET mavenStarted=?, mavenEnded=? where id=?");
						eps.setLong(1, ex.start);
						eps.setLong(2, ex.end);
						eps.setInt(3, testID);
						eps.execute();
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					
					continue;
				}
				int moduleID = 0;
				try {
					ps.setInt(1, testID);
					ps.setLong(2, ex.start);
					ps.setLong(3, ex.end);
					ps.setInt(4, (int) (ex.end - ex.start));
					ps.setString(5, ex.phase);
					ps.setString(6, ex.goal);
					ps.setString(7, ex.project);
					ps.setString(8, ex.mojo);
					ps.setInt(9, ex.success ? 1 : 0);
					ps.setString(10, ex.id);
					ps.executeUpdate();
					ResultSet rs = ps.getGeneratedKeys();
					rs.next();
					moduleID = rs.getInt(1);
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
		}
	});

	static class MojoExecution {
		String project;
		String phase;
		String goal;
		String mojo;
		String id;
		
		String testClasspath;
		String testSourcepath;
		String compileRoots;
		String testOutputDirectory;
		String outputDirectory;
		String surefireConfiguration;
		List<String> dependencies;
		long start;
		long end;
		boolean success;
		String baseDir;
		boolean isStart;
		boolean isSession;
		
		public boolean equals(ExecutionEvent event) {
			return event != null && event.getMojoExecution() != null && event.getProject() != null && 
					event.getMojoExecution().getGroupId() != null &&
					event.getMojoExecution().getArtifactId() != null &&
					(event.getProject().getGroupId() + ":" + event.getProject().getArtifactId()).equals(project)
					&& (event.getMojoExecution().getGroupId() + ":" + event.getMojoExecution().getArtifactId()).equals(mojo) && (phase.equals(event.getMojoExecution().getLifecyclePhase()) || (phase.equals("cli") && event.getMojoExecution().getLifecyclePhase() == null))
					&& goal.equals(event.getMojoExecution().getGoal());
		}
	}

	@Requirement
	public void initialize() throws InitializationException {
		delegate = new ExecutionEventLogger();
	}

	@Override
	public void forkedProjectFailed(ExecutionEvent event) {
		delegate.forkedProjectFailed(event);
	}

	@Override
	public void forkedProjectStarted(ExecutionEvent event) {
		delegate.forkedProjectStarted(event);
	}

	@Override
	public void forkedProjectSucceeded(ExecutionEvent event) {
		delegate.forkedProjectSucceeded(event);
	}

	@Override
	public void forkFailed(ExecutionEvent event) {
		delegate.forkFailed(event);
	}

	@Override
	public void forkStarted(ExecutionEvent event) {
		delegate.forkStarted(event);
	}

	@Override
	public void forkSucceeded(ExecutionEvent event) {
		delegate.forkSucceeded(event);
	}

	@Override
	public void mojoSkipped(ExecutionEvent event) {
		delegate.mojoSkipped(event);
	}

	LinkedList<MojoExecution> pendingEvents = new LinkedList<LifecycleLogger.MojoExecution>();
	LinkedList<MojoExecution> finishedEvents = new LinkedList<LifecycleLogger.MojoExecution>();

	@Override
	public void mojoStarted(ExecutionEvent event) {
		delegate.mojoStarted(event);
		if (event.getProject() != null && event.getMojoExecution() != null) {
			MojoExecution ex = new MojoExecution();
			ex.project = event.getProject().getGroupId() + ":" + event.getProject().getArtifactId();
			ex.mojo = event.getMojoExecution().getGroupId() + ":" + event.getMojoExecution().getArtifactId();
			ex.phase = event.getMojoExecution().getLifecyclePhase();
			ex.goal = event.getMojoExecution().getGoal();
			ex.id = event.getMojoExecution().getExecutionId();
			ex.start = System.currentTimeMillis();
			if(ex.phase == null)
				ex.phase = "cli";
			System.out.println(">>>" + ex.mojo + " " + ex.goal + " " + ex.phase);
			if(event.getMojoExecution().getArtifactId().equals("maven-surefire-plugin"))
			{
				//Surefire test starting
				System.out.println("Detected surefire run in " + ex.phase + "." + ex.goal);
				try {
					ex.dependencies = new LinkedList<String>();
					for(Dependency d : event.getProject().getDependencies())
					{
						ex.dependencies.add(d.getGroupId()+":"+d.getArtifactId());
					}
					if(event.getProject().getBasedir() != null)
						ex.baseDir = event.getProject().getBasedir().getAbsolutePath();
					if(event.getMojoExecution().getPlugin() != null && event.getMojoExecution().getPlugin().getConfiguration() != null)
						ex.surefireConfiguration = event.getMojoExecution().getPlugin().getConfiguration().toString();
					ex.testClasspath = "";
					
					for(String s : event.getProject().getTestClasspathElements())
						ex.testClasspath += s+":";
					for(Artifact a : event.getProject().getDependencyArtifacts())
						ex.testClasspath += a.getFile()+":";
					if(ex.testClasspath.length() > 0)
						ex.testClasspath = ex.testClasspath.substring(0,ex.testClasspath.length() -1 );
					ex.compileRoots = "";
					for(String s : event.getProject().getCompileSourceRoots())
						ex.compileRoots += s+":";
					if(ex.compileRoots.length() > 0)
						ex.compileRoots = ex.compileRoots.substring(0,ex.compileRoots.length() -1 );
					ex.testSourcepath = "";
					for(String s : event.getProject().getTestCompileSourceRoots())
						ex.testSourcepath += s+":";
					if(ex.compileRoots.length() > 0)
						ex.testSourcepath = ex.testSourcepath.substring(0,ex.testSourcepath.length() -1 );
					ex.testOutputDirectory = event.getProject().getBuild().getTestOutputDirectory();
					ex.outputDirectory = event.getProject().getBuild().getOutputDirectory();
										
				} catch (DependencyResolutionRequiredException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if(ex.phase != null && ex.goal != null)
			synchronized (pendingEvents) {
				pendingEvents.add(ex);
			}
		}
	}

	@Override
	public void mojoSucceeded(ExecutionEvent event) {
		fireMojoExecution(event, false);
		delegate.mojoSucceeded(event);
	}

	@Override
	public void mojoFailed(ExecutionEvent event) {
		fireMojoExecution(event, true);
		delegate.mojoFailed(event);
	}

	private void fireMojoExecution(ExecutionEvent event, boolean failed) {
		MojoExecution ex = null;
		synchronized (pendingEvents) {
			if(pendingEvents.isEmpty())
				return;
			ex = pendingEvents.getFirst();
			if (ex.equals(event)) {
				ex = pendingEvents.pop();
			} else {
				Iterator<MojoExecution> iter = pendingEvents.iterator();
				boolean found = false;
				while (iter.hasNext()) {
					ex = iter.next();
					if (ex.equals(event)) {
						found = true;
						iter.remove();
						break;
					}
				}
				if (!found)
					return;
			}
		}
		if(!inserter.isAlive())
		{
			OKToEnd = false;
			inserter.setDaemon(true);
			inserter.start();
		}
		ex.end = System.currentTimeMillis();
		ex.success = !failed;
		synchronized (finishedEvents) {
			finishedEvents.add(ex);
			finishedEvents.notify();
		}

	}

	@Override
	public void projectDiscoveryStarted(ExecutionEvent event) {
		delegate.projectDiscoveryStarted(event);
	}

	@Override
	public void projectFailed(ExecutionEvent event) {
		delegate.projectFailed(event);
	}

	@Override
	public void projectSkipped(ExecutionEvent event) {
		delegate.projectSkipped(event);
	}

	@Override
	public void projectStarted(ExecutionEvent event) {
		delegate.projectStarted(event);
	}

	@Override
	public void projectSucceeded(ExecutionEvent event) {
		delegate.projectSucceeded(event);
	}

	@Override
	public void sessionEnded(ExecutionEvent event) {
		delegate.sessionEnded(event);
		ex.end = System.currentTimeMillis();
		synchronized (finishedEvents) {
			finishedEvents.add(ex);
			finishedEvents.notify();
		}
		synchronized (finishedEvents) {
			inserterShouldDie = true;
			finishedEvents.notify();
		}
		synchronized (finishedEvents) {
			while (!OKToEnd)
				try {
					finishedEvents.wait();
				} catch (InterruptedException ex) {

				}
		}
	}

	MojoExecution ex = new MojoExecution();

	@Override
	public void sessionStarted(ExecutionEvent event) {
		delegate.sessionStarted(event);
		ex.start = System.currentTimeMillis();
		ex.isSession = true;
	}

	public void configure(ExecutionListener executionListener) {
		this.delegate = executionListener;
	}
}
