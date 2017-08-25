package org.deflaker.maven;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Extension;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.repository.RemoteRepository;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "diffcov-ext")
public class DeflakerLifecycleParticipant extends AbstractMavenLifecycleParticipant {

	final static int NUM_RERUNS = Integer.valueOf(System.getProperty("deflaker.rerunFlakies", "5"));
	static String DEFLAKER_VERSION = "1.4";
	static String SUREFIRE_RERUNNER_VERSION_SUFFIX = "4";
	static boolean isSnapshotVersion;
	static String PATH_TO_AGENT_BOOTPATH_JAR;
	static String PATH_TO_AGENT ;
	static String SNAPSHOT_REPO = "https://oss.sonatype.org/content/repositories/snapshots/";
	
	static void initVersionDependentVars()
	{
		isSnapshotVersion = DEFLAKER_VERSION.endsWith("-SNAPSHOT");
		PATH_TO_AGENT_BOOTPATH_JAR = "/org/deflaker/deflaker-agent-bootpath/"+DEFLAKER_VERSION+"/deflaker-agent-bootpath-"+DEFLAKER_VERSION+".jar";
		PATH_TO_AGENT = "/org/deflaker/deflaker-agent/"+DEFLAKER_VERSION+"/deflaker-agent-"+DEFLAKER_VERSION+".jar";
	}
	String gitDir;
	String diffFile;
//	@Requirement(role = ExecutionListener.class, hint="swe-mysql-logger")
	private LifecycleLogger logger;

	private void addExternalRepo(MavenProject p)
	{
		if(p == null)
			return;
		if(System.getProperty("diffcov.mysql") != null)
		{
			System.out.println("Adding local repo");
			//Also hack on the nfs repo
			RemoteRepository.Builder builder = new RemoteRepository.Builder("deflaker.study.repo", "default", "file:///repo/m2/repository/");
			p.getRemotePluginRepositories().add(builder.build());
			p.getRemoteProjectRepositories().add(builder.build());
			p.getRemoteArtifactRepositories().add(
					new MavenArtifactRepository("deflaker.study.repo", "file:///repo/m2/repository/", new DefaultRepositoryLayout(), new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE),
							new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE)));
			Repository repo = new Repository();
			repo.setId("deflaker.study.repo");
			repo.setUrl("file:///repo/m2/repository/");
			RepositoryPolicy policy = new RepositoryPolicy();
			policy.setEnabled(true);
			policy.setUpdatePolicy("always");
			repo.setSnapshots(policy);
			repo.setReleases(policy);
			p.getRepositories().add(repo);
			
			p.getPluginRepositories().add(repo);
		}
		if(!isSnapshotVersion)
			return;
		RemoteRepository.Builder builder = new RemoteRepository.Builder("deflaker.snapshots", "default", SNAPSHOT_REPO);
		p.getRemotePluginRepositories().add(builder.build());
		p.getRemoteProjectRepositories().add(builder.build());
		p.getRemoteArtifactRepositories().add(
				new MavenArtifactRepository("deflaker.snapshots", SNAPSHOT_REPO, new DefaultRepositoryLayout(), new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE),
						new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE)));
		Repository repo = new Repository();
		repo.setId("deflaker.snapshots");
		repo.setUrl(SNAPSHOT_REPO);
		RepositoryPolicy policy = new RepositoryPolicy();
		policy.setEnabled(true);
		policy.setUpdatePolicy("always");
		repo.setSnapshots(policy);
		repo.setReleases(policy);
		p.getRepositories().add(repo);
		
		p.getPluginRepositories().add(repo);

	}

	@Override
	public void afterSessionStart(MavenSession session) throws MavenExecutionException {
//		addExternalRepo(session.getCurrentProject());
		if(isSnapshotVersion)
			session.getRequest().getPluginArtifactRepositories().add(new MavenArtifactRepository("deflaker.snapshots", SNAPSHOT_REPO, new DefaultRepositoryLayout(), new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE),
				new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE)));
		if(System.getProperty("diffcov.mysql") != null)
			session.getRequest().getPluginArtifactRepositories().add(new MavenArtifactRepository("deflaker.study.local", "file:///repo/m2/repository/", new DefaultRepositoryLayout(), new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE),
					new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE)));
//		session.getRequest()
//				.getRemoteRepositories()
//				.add(new MavenArtifactRepository("jb.snapshots", "https://mymavenrepo.com/repo/6hsJXoD6yIwTibr6Kuza/", new DefaultRepositoryLayout(), new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE),
//						new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE)));

		super.afterSessionStart(session);
	}

	String covType;
	@Override
	public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
		
		//Find out what version we need
		for (Extension e : session.getCurrentProject().getBuildExtensions()) {
			if(e.getArtifactId().equals("deflaker-maven-extension") && e.getGroupId().equals("org.deflaker"))
			{
				System.out.println("Deflaker, version " + e.getVersion() + " loading...");
				DEFLAKER_VERSION = e.getVersion();
			}
		}
		initVersionDependentVars();
		
		for (MavenProject p : session.getProjects()) {
			removeAnnoyingPlugins(p);
			addExternalRepo(p);
		}
		addExternalRepo(session.getCurrentProject().getParent());

		if(System.getProperty("diffcov.mysql") != null)
		{
			logger = new LifecycleLogger();
			logger.configure(session.getRequest().getExecutionListener());
			session.getRequest().setExecutionListener(logger);
		}
		covType = System.getProperty("diffcov.covtype", "diffcov");
		switch (covType) {
		case "diffcov":
			configureDiffCov(session);
			break;
		case "jacoco-pertest":
		case "jacoco":
			try {
				configureJacoco(session);
			} catch (MojoFailureException e) {
				throw new MavenExecutionException("Unable to install diffcov", e);
			}
			break;
		case "clover":
			try {
				configureClover(session);
			} catch (MojoFailureException e) {
				throw new MavenExecutionException("Unable to install diffcov", e);
			}
			break;
		case "cobertura-pertest":
		case "cobertura":
			try {
				configureCobertura(session);
			} catch (MojoFailureException e) {
				throw new MavenExecutionException("Unable to install diffcov", e);
			}
			break;
		case "ekstazi":
			try {
				configureEkstazi(session);
			} catch (MojoFailureException e) {
				throw new MavenExecutionException("Unable to install diffcov", e);
			}
			break;
		case "none":
			try {
				configureLogger(session);
			} catch (MojoFailureException e) {
				throw new MavenExecutionException("Unable to install diffcov", e);
			}
			break;
		case "none-reruns":
			try {
				configureLoggerWithReruns(session);
			} catch (MojoFailureException e) {
				throw new MavenExecutionException("Unable to install diffcov", e);
			}
			break;
		default:
			throw new IllegalStateException();
		}
	}
	private void configureLoggerWithReruns(MavenSession session) throws MojoFailureException
	{
		for (MavenProject p : session.getProjects()) {
			addSurefireLoggerWithoutCoverageWithReruns(p,false);
			addSurefireLoggerWithoutCoverageWithReruns(p,true);
		}
	}
	private void configureLogger(MavenSession session) throws MojoFailureException
	{
		for (MavenProject p : session.getProjects()) {
			addSurefireLoggerWithoutCoverage(p,false);
			addSurefireLoggerWithoutCoverage(p,true);
		}
	}

	private void configureJacoco(MavenSession session) throws MojoFailureException
	{
		for (MavenProject p : session.getProjects()) {
			Plugin newPlug = new Plugin();
			newPlug.setArtifactId("jacoco-maven-plugin");
			newPlug.setGroupId("org.jacoco");
			newPlug.setVersion("0.7.9");
			p.getBuild().addPlugin(newPlug);
			addSurefireLoggerWithoutCoverage(p,false);
			addSurefireLoggerWithoutCoverage(p,true);
		}
	}
	
	private void configureCobertura(MavenSession session) throws MojoFailureException
	{
		for (MavenProject p : session.getProjects()) {
			Plugin newPlug = new Plugin();
			newPlug.setArtifactId("cobertura-maven-plugin");
			newPlug.setGroupId("org.codehaus.mojo");
			newPlug.setVersion("2.7");

			p.getBuild().addPlugin(newPlug);
			
			Dependency d = new Dependency();
			d.setArtifactId("cobertura-maven-plugin");
			d.setGroupId("org.codehaus.mojo");
			d.setVersion("2.7");
			p.getDependencies().add(d);
			addSurefireLoggerWithoutCoverage(p,false);
			addSurefireLoggerWithoutCoverage(p,true);
		}
	}
	
	private void configureClover(MavenSession session) throws MojoFailureException
	{
		for (MavenProject p : session.getProjects()) {
			Plugin newPlug = new Plugin();

			newPlug.setArtifactId("clover-maven-plugin");
			newPlug.setGroupId("org.openclover");
			newPlug.setVersion("4.2.0");

			p.getBuild().addPlugin(newPlug);
			
			Dependency d = new Dependency();
			d.setArtifactId("clover-maven-plugin");
			d.setGroupId("org.openclover");
			d.setVersion("4.2.0");
			p.getDependencies().add(d);
			addSurefireLoggerWithoutCoverage(p,false);
			addSurefireLoggerWithoutCoverage(p,true);
		}
	}
	private void configureEkstazi(MavenSession session) throws MojoFailureException
	{
		for (MavenProject p : session.getProjects()) 
		{
			Plugin newPlug = new Plugin();
			newPlug.setArtifactId("ekstazi-maven-plugin");
			newPlug.setGroupId("org.ekstazi");
			newPlug.setVersion("4.6.1");

			PluginExecution ex = new PluginExecution();
			ex.setId("ekstazi");
			ex.setGoals(Collections.singletonList("select"));
			ex.setPhase("process-test-classes");
			Xpp3Dom config = new Xpp3Dom("configuration");
			Xpp3Dom forceAll = new Xpp3Dom("forceall");
			forceAll.setValue("true");
			config.addChild(forceAll);
			ex.setConfiguration(config);
			newPlug.addExecution(ex);
			p.getBuild().addPlugin(newPlug);
			addSurefireLoggerWithoutCoverage(p,false);
			addSurefireLoggerWithoutCoverage(p,true);
			}

	}
	public final static boolean ALL_COVERAGE = Boolean.valueOf(System.getProperty("diffcov.allCoverage","false"));

	private void configureDiffCov(MavenSession session)
	{
		try {
			if(System.getenv("TRAVIS") == null)
			{
				String baseDir;
				
				if(System.getenv("DIFFCOV_BASE_DIR") != null)
					baseDir = System.getenv("DIFFCOV_BASE_DIR");
				else
					baseDir = session.getTopLevelProject().getBasedir().toString();
				this.diffFile = baseDir + "/.diffCache";
				gitDir = System.getProperty("gitDir", baseDir + "/.git");
			}
			else
			{
				gitDir = System.getenv("TRAVIS_BUILD_DIR") + "/.git";
				this.diffFile = session.getTopLevelProject().getBasedir() + "/.diffCache";
				System.out.println("DiffCov using env variables from travis: ");
				System.out.println("\tTRAVIS_BUILD_DIR=" + System.getenv("TRAVIS_BUILD_DIR"));
				System.out.println("\tTRAVIS_BUILD_ID=" + System.getenv("TRAVIS_BUILD_ID"));
			}

			File diffFile = new File(this.diffFile);
			if (diffFile.exists())
				diffFile.delete();
			if(ALL_COVERAGE)
			{
				//TODO collect all of the packages in this project
				File javaFile = new File(this.diffFile+".builddirs");
				if(javaFile.exists())
					javaFile.delete();
				try{
					FileWriter fw = new FileWriter(javaFile);
					for (MavenProject p : session.getProjects()) {
						fw.write(p.getBuild().getSourceDirectory() + "\n");
						fw.write(p.getBuild().getTestSourceDirectory() + "\n");
					}
					fw.close();
				} catch (IOException ex) {
					ex.printStackTrace();
				}
			}
			for (MavenProject p : session.getProjects()) {
//				addExternalRepo(p);
				
				boolean found = injectToSurefireFailsafe(p, session.getLocalRepository().getBasedir() + PATH_TO_AGENT, session.getLocalRepository().getBasedir() + PATH_TO_AGENT_BOOTPATH_JAR, false);
				found |= injectToSurefireFailsafe(p, session.getLocalRepository().getBasedir() + PATH_TO_AGENT, session.getLocalRepository().getBasedir() + PATH_TO_AGENT_BOOTPATH_JAR, true);
				if (!found) {
					System.err.println("diffcov WARN: couldn't find surefire or failsafe on " + p.getName());
				}
				removeAnnoyingPlugins(p);
			}

		} catch (MojoExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (MojoFailureException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	static HashSet<String> disabledPlugins = new HashSet<String>();
	static
	{
		disabledPlugins.add("maven-enforcer-plugin");
		disabledPlugins.add("license-maven-plugin");
		disabledPlugins.add("maven-duplicate-finder-plugin");
		disabledPlugins.add("apache-rat-plugin");
		disabledPlugins.add("cobertura-maven-plugin");
		disabledPlugins.add("jacoco-maven-plugin");
		disabledPlugins.add("maven-dependency-versions-check-plugin");
		disabledPlugins.add("duplicate-finder-maven-plugin");
	}
	private void removeAnnoyingPlugins(MavenProject proj) {
		LinkedList<Plugin> plugsToRemove = new LinkedList<Plugin>();
		
		for(Plugin p : proj.getBuildPlugins())
		{
			if(disabledPlugins.contains(p.getArtifactId()))
			{
				plugsToRemove.add(p);
				System.out.println("Warning: Deflaker disabling incompatible " + p.getGroupId()+":"+p.getArtifactId() + " from " + proj.getArtifactId());
			}
			if(System.getProperty("diffcov.mysql") != null)
			{
				//fix for checkstyle in evaluation
				if(p.getArtifactId().equals("maven-antrun-plugin") && proj.getName().contains("checkstyle"))
				{
					PluginExecution del = null;
					for(PluginExecution pe : p.getExecutions())
					{
						if(pe.getId().equals("ant-phase-verify"))
							del = pe;
					}
					if(del != null)
						p.getExecutions().remove(del);
				}
			}
		}
		proj.getBuildPlugins().removeAll(plugsToRemove);

		//Also, fix terrible junit deps
		for(Dependency d : proj.getDependencies())
		{
			if("junit".equals(d.getGroupId()) && "junit".equals(d.getArtifactId()) && "4.2".equals(d.getVersion()))
			{
				d.setVersion("4.6");
			}
		}
	}

	public void addSurefireLoggerWithoutCoverage(MavenProject project, boolean doFailsafe) throws MojoFailureException
	{
		Plugin p = null;
		for (Plugin o : project.getBuildPlugins()) {
			if (!doFailsafe && o.getArtifactId().equals("maven-surefire-plugin") && o.getGroupId().equals("org.apache.maven.plugins"))
				p = o;
			else if (doFailsafe && o.getArtifactId().equals("maven-failsafe-plugin") && o.getGroupId().equals("org.apache.maven.plugins"))
				p = o;
		}
		
		if (p == null)
			return;
		boolean testNG = false;
		for(Dependency d : project.getDependencies())
		{
			if(d.getGroupId().equals("org.testng"))
				testNG = true;
		}
		String version = p.getVersion();
		if (version != null) {
			try {
				version = version.substring(2);
				if (!"18.1".equals(version) && !"19.1".equals(version)) {
					int vers = Integer.valueOf(version);
					if (vers < 17)
						p.setVersion("2.18");
				}
			} catch (NumberFormatException ex) {
				p.setVersion("2.18");
			}
		}
		Dependency d = new Dependency();
		d.setArtifactId("deflaker-test-listener");
		d.setGroupId("org.deflaker");
		d.setVersion(DEFLAKER_VERSION);
		d.setScope("test");
		project.getDependencies().add(d);
		for (PluginExecution pe : p.getExecutions()) {

			Xpp3Dom config = (Xpp3Dom) pe.getConfiguration();
			if (config == null)
				config = new Xpp3Dom("configuration");
			injectConfig(config,testNG, false);
			p.setConfiguration(config);
			pe.setConfiguration(config);			
		}
		p.getDependencies().clear();
	}
	
	public void addSurefireLoggerWithoutCoverageWithReruns(MavenProject project, boolean doFailsafe) throws MojoFailureException
	{
		Plugin p = null;
		for (Plugin o : project.getBuildPlugins()) {
			if (!doFailsafe && o.getArtifactId().equals("maven-surefire-plugin") && o.getGroupId().equals("org.apache.maven.plugins"))
				p = o;
			else if (doFailsafe && o.getArtifactId().equals("maven-failsafe-plugin") && o.getGroupId().equals("org.apache.maven.plugins"))
				p = o;
		}
		
		if (p == null)
			return;
		boolean testNG = false;
		for(Dependency d : project.getDependencies())
		{
			if(d.getGroupId().equals("org.testng"))
				testNG = true;
//			else if(d.getGroupId().equals("junit"))
//				d.setVersion("4.12"); //Force new junit version
		}
		if(testNG)
			System.out.println("Detected TestNG...");
		LinkedList<PluginExecution> toAdd = new LinkedList<PluginExecution>();
		boolean shouldUseJUnit47 = false;
		for (PluginExecution pe : p.getExecutions()) {
//			if (LAZY_COV) {
//				Xpp3Dom config = (Xpp3Dom) pe.getConfiguration();
//				if (config == null)
//					config = new Xpp3Dom("configuration");
//				injectConfig(config, testNG, false); //Set up current config
//				pe.setConfiguration(config);
//
//				config = new Xpp3Dom(config);
//				PluginExecution pe2 = new PluginExecution();
//				pe2.getGoals().addAll(pe.getGoals());
//				pe2.setPhase(pe.getPhase());
//				pe2.setId(pe.getId()+"-rerunfailures");
////				pe2.setPriority(Integer.MAX_VALUE);
//				injectConfig(config, pathToAgent, pathToAgentBootpath, gitDir, outputFile.toString(), testNG, true);
//
//				pe2.setConfiguration(config);
//				toAdd.add(pe2);
//			}
//			else 
			{
				Xpp3Dom config = (Xpp3Dom) pe.getConfiguration();
				if (config == null)
					config = new Xpp3Dom("configuration");
				shouldUseJUnit47 |= shouldUseJunitCoreProvider(config);
				Xpp3Dom config2 = new Xpp3Dom(config);

				injectConfigWithRerunsNoDiffcov(project, config,testNG, false, doFailsafe);
				p.setConfiguration(config);
				pe.setConfiguration(config);
				if (NUM_RERUNS > 0) {
					PluginExecution pe2 = new PluginExecution();

					pe2.getGoals().addAll(pe.getGoals());
					pe2.setPhase(pe.getPhase());
					pe2.setId(pe.getId() + "-rerunfailures");
					// pe2.setPriority(Integer.MAX_VALUE);
					injectConfigWithRerunsNoDiffcov(project,config2,testNG, true, doFailsafe);

					pe2.setConfiguration(config2);
					toAdd.add(pe2);
				}
			}
		}
		String version = p.getVersion();
		String runnerVersion = "2.18."+SUREFIRE_RERUNNER_VERSION_SUFFIX;
		if (version != null) {
			try {
				version = version.split("\\.")[1];
				int vers = Integer.valueOf(version);
				if (vers < 18) {
					vers = 18;
					p.setVersion("2.18");
				}
				switch(vers){
				case 18:
//					runnerVersion = "2.18.1-SNAPSHOT";
					break;
				case 19:
				case 20:
					runnerVersion = "2.19."+SUREFIRE_RERUNNER_VERSION_SUFFIX;
					p.setVersion("2.19.1");
					break;
				}
			} catch (NumberFormatException ex) {
				p.setVersion("2.18");
			}
		}
		if(testNG)
		{
			runnerVersion = "2.19."+SUREFIRE_RERUNNER_VERSION_SUFFIX;//only have old version for junit for now
			p.setVersion("2.19.1");
		}
		else
			if(System.getProperty("diffcov.mysql") != null)
			{
				if(p.getVersion() == null || p.getVersion().equals("2.18.1") || p.getVersion().equals("2.18"))
				{
					//Stupid surefire bug.
					p.setVersion("2.18.1-deflaker");
				}
			}
		if(shouldUseJUnit47)
		{
			//Only have 2.19.1 version for junit47
			p.setVersion("2.19.1");
			runnerVersion = "2.19."+SUREFIRE_RERUNNER_VERSION_SUFFIX;
		}
		Dependency d = new Dependency();
		d.setArtifactId("deflaker-test-listener");
		d.setGroupId("org.deflaker");
		d.setVersion(DEFLAKER_VERSION);
		d.setScope("test");
		project.getDependencies().add(d);
		if(NUM_RERUNS > 0)
		{
			//Foricbly remove any existing deps
			p.getDependencies().clear();
			d = new Dependency();
			if(testNG)
				d.setArtifactId("deflaker-surefire-reexec-testng");
			else
			{
				if(shouldUseJUnit47)
					d.setArtifactId("deflaker-surefire-reexec-junit47");
				else
					d.setArtifactId("deflaker-surefire-reexec-junit4");
			}
			d.setGroupId("org.deflaker");
			d.setVersion(runnerVersion);
			p.addDependency(d);
			for(PluginExecution pe : toAdd)
				p.addExecution(pe);
		}



	}
//	final static boolean LAZY_COV = System.getProperty("diffcov.lazycov") != null;

	public boolean injectToSurefireFailsafe(MavenProject project, String pathToAgent, String pathToAgentBootpath, boolean doFailsafe) throws MojoExecutionException, MojoFailureException {
		Plugin p = null;
		for (Plugin o : project.getBuildPlugins()) {
			if (!doFailsafe && o.getArtifactId().equals("maven-surefire-plugin") && o.getGroupId().equals("org.apache.maven.plugins"))
				p = o;
			else if (doFailsafe && o.getArtifactId().equals("maven-failsafe-plugin") && o.getGroupId().equals("org.apache.maven.plugins"))
				p = o;
		}
		
		if (p == null)
			return false;
		boolean testNG = false;
		for(Dependency d : project.getDependencies())
		{
			if(d.getGroupId().equals("org.testng"))
				testNG = true;
//			else if(d.getGroupId().equals("junit"))
//				d.setVersion("4.12"); //Force new junit version
		}
		if(testNG)
			System.out.println("Detected TestNG...");
		
		File outputFile = new File(project.getBasedir(), "/target/diffcov.log");
		if (outputFile.exists())
			outputFile.delete();

		LinkedList<PluginExecution> toAdd = new LinkedList<PluginExecution>();
		boolean shouldUseJUnit47 = false;
		for (PluginExecution pe : p.getExecutions()) {
//			if (LAZY_COV) {
//				Xpp3Dom config = (Xpp3Dom) pe.getConfiguration();
//				if (config == null)
//					config = new Xpp3Dom("configuration");
//				injectConfig(config, testNG, false); //Set up current config
//				pe.setConfiguration(config);
//
//				config = new Xpp3Dom(config);
//				PluginExecution pe2 = new PluginExecution();
//				pe2.getGoals().addAll(pe.getGoals());
//				pe2.setPhase(pe.getPhase());
//				pe2.setId(pe.getId()+"-rerunfailures");
////				pe2.setPriority(Integer.MAX_VALUE);
//				injectConfig(config, pathToAgent, pathToAgentBootpath, gitDir, outputFile.toString(), testNG, true);
//
//				pe2.setConfiguration(config);
//				toAdd.add(pe2);
//			}
//			else 
			{
				Xpp3Dom config = (Xpp3Dom) pe.getConfiguration();
				if (config == null)
					config = new Xpp3Dom("configuration");
				shouldUseJUnit47 |= shouldUseJunitCoreProvider(config);
				Xpp3Dom config2 = new Xpp3Dom(config);

				injectConfig(config, pathToAgent, pathToAgentBootpath, gitDir, outputFile.toString(), testNG, false, doFailsafe);
				p.setConfiguration(config);
				pe.setConfiguration(config);
				if (NUM_RERUNS > 0) {
					PluginExecution pe2 = new PluginExecution();

					pe2.getGoals().addAll(pe.getGoals());
					pe2.setPhase(pe.getPhase());
					pe2.setId(pe.getId() + "-rerunfailures");
					// pe2.setPriority(Integer.MAX_VALUE);
					injectConfig(config2, pathToAgent, pathToAgentBootpath, gitDir, outputFile.toString(), testNG, true, doFailsafe);

					pe2.setConfiguration(config2);
					toAdd.add(pe2);
				}
			}
		}
		String version = p.getVersion();
		String runnerVersion = "2.18."+SUREFIRE_RERUNNER_VERSION_SUFFIX;
		if (version != null) {
			try {
				version = version.split("\\.")[1];
				int vers = Integer.valueOf(version);
				if (vers < 18) {
					vers = 18;
					p.setVersion("2.18");
				}
				switch(vers){
				case 18:
//					runnerVersion = "2.18.1-SNAPSHOT";
					break;
				case 19:
				case 20:
					runnerVersion = "2.19."+SUREFIRE_RERUNNER_VERSION_SUFFIX;
					p.setVersion("2.19.1");
					break;
				}
			} catch (NumberFormatException ex) {
				p.setVersion("2.18");
			}
		}
		if(testNG)
		{
			runnerVersion = "2.19."+SUREFIRE_RERUNNER_VERSION_SUFFIX;//only have old version for junit for now
			p.setVersion("2.19.1");
		}
		else
			if(System.getProperty("diffcov.mysql") != null)
			{
				if(p.getVersion() == null || p.getVersion().equals("2.18.1") || p.getVersion().equals("2.18"))
				{
					//Stupid surefire bug.
					p.setVersion("2.18.1-deflaker");
				}
			}
		if(shouldUseJUnit47)
		{
			//Only have 2.19.1 version for junit47
			p.setVersion("2.19.1");
			runnerVersion = "2.19."+SUREFIRE_RERUNNER_VERSION_SUFFIX;
		}
		Dependency d = new Dependency();
		d.setArtifactId("deflaker-test-listener");
		d.setGroupId("org.deflaker");
		d.setVersion(DEFLAKER_VERSION);
		d.setScope("test");
		project.getDependencies().add(d);
		if(NUM_RERUNS > 0)
		{
			//Foricbly remove any existing deps
			p.getDependencies().clear();
			d = new Dependency();
			if(testNG)
				d.setArtifactId("deflaker-surefire-reexec-testng");
			else
			{
				if(shouldUseJUnit47)
					d.setArtifactId("deflaker-surefire-reexec-junit47");
				else
					d.setArtifactId("deflaker-surefire-reexec-junit4");
			}
			d.setGroupId("org.deflaker");
			d.setVersion(runnerVersion);
			p.addDependency(d);
			for(PluginExecution pe : toAdd)
				p.addExecution(pe);
		}
		
		// Add the reporting plugin
		Plugin reportingPlugin = null;
		//Look to see if we already have added it to this project
		for(Plugin _p : 		project.getBuild().getPlugins())
		{
			if(_p.getArtifactId().equals("deflaker-maven-plugin"))
				reportingPlugin = _p;
		}
		if (reportingPlugin == null) {
			reportingPlugin = new Plugin();
			reportingPlugin.setArtifactId("deflaker-maven-plugin");
			reportingPlugin.setGroupId("org.deflaker");
			reportingPlugin.setVersion(DEFLAKER_VERSION);
			project.getBuild().addPlugin(reportingPlugin);
		}
		{
			PluginExecution repExec = new PluginExecution();
			if(doFailsafe)
			{
				repExec.setId("deflaker-report-integration-tests");
				repExec.setPhase("verify");
			}
			else
			{
				repExec.setId("deflaker-report-tests");
				repExec.setPhase("test");
			}
			repExec.setGoals(Collections.singletonList("report"));
			Xpp3Dom reportConfig = new Xpp3Dom("configuration");
			Xpp3Dom gitDirProp = new Xpp3Dom("gitDir");
			reportConfig.addChild(gitDirProp);
			gitDirProp.setValue(gitDir);
			Xpp3Dom logFileProp = new Xpp3Dom("covFile");
			reportConfig.addChild(logFileProp);
			logFileProp.setValue(outputFile.toString());
			Xpp3Dom diffFile = new Xpp3Dom("diffFile");
			diffFile.setValue(this.diffFile);
			reportConfig.addChild(diffFile);
			
			Xpp3Dom reportedLog = new Xpp3Dom("reportedTestsFile");
			String reportFile = project.getBuild().getOutputDirectory()+'/'+".deflaker-reported";
			File f = new File(reportFile);
			if(f.exists())
				f.delete();
			reportedLog.setValue(reportFile);
			
			reportConfig.addChild(reportedLog);
			
			if(doFailsafe)
			{
				Xpp3Dom isFailsafeDom = new Xpp3Dom("isFailsafe");
				isFailsafeDom.setValue("true");
				reportConfig.addChild(isFailsafeDom);
			}
			repExec.setConfiguration(reportConfig);
			reportingPlugin.addExecution(repExec);
		}
		{
			PluginExecution repExec = new PluginExecution();
			repExec.setId("deflaker-diff");
			repExec.setPhase("process-test-resources");
			repExec.setGoals(Collections.singletonList("diff"));
			Xpp3Dom reportConfig = new Xpp3Dom("configuration");
			Xpp3Dom gitDirProp = new Xpp3Dom("gitDir");
			reportConfig.addChild(gitDirProp);
			gitDirProp.setValue(gitDir);
			Xpp3Dom logFileProp = new Xpp3Dom("covFile");
			reportConfig.addChild(logFileProp);
			logFileProp.setValue(outputFile.toString());
			Xpp3Dom diffFile = new Xpp3Dom("diffFile");
			diffFile.setValue(this.diffFile);
			reportConfig.addChild(diffFile);
			repExec.setConfiguration(reportConfig);
			reportingPlugin.addExecution(repExec);
		}
		return true;
	}
	
	void fixForkMode(Xpp3Dom config, boolean forkPerTest)
	{
		Xpp3Dom forkMode = config.getChild("forkMode");
		boolean isSetToFork = false;
		if(forkPerTest)
		{
			if(forkMode != null)
				forkMode.setValue("perTest");
			else
			{
				Xpp3Dom forkCount = config.getChild("forkCount");
				if(forkCount != null)
					forkCount.setValue("1");
				else
				{
					forkCount = new Xpp3Dom("forkCount");
					forkCount.setValue("1");
					config.addChild(forkCount);
				}
				Xpp3Dom reuseForks = config.getChild("reuseForks");
				if(reuseForks != null)
					reuseForks.setValue("false");
				else
				{
					reuseForks = new Xpp3Dom("reuseForks");
					reuseForks.setValue("false");
					config.addChild(reuseForks);
				}
			}
			return;
		}
		if (forkMode != null && (forkMode.getValue().equalsIgnoreCase("never") || forkMode.getValue().equalsIgnoreCase("perthread")))
		{
			System.out.println("Overriding forkmode from " + forkMode.getValue() +  " to once ");
			forkMode.setValue("once");
			isSetToFork = true;
//			throw new MojoFailureException("ForkMode can only be once (for now...)");
		}

		Xpp3Dom forkCount = config.getChild("forkCount");
		if (forkCount != null && !forkCount.getValue().equals("1"))
		{
			isSetToFork = true;
			forkCount.setValue("1");
//			throw new MojoFailureException("Fork count must be 1 for now");
		}

		Xpp3Dom reuseForks = config.getChild("reuseForks");
		if (reuseForks != null && reuseForks.getValue().equals("false"))
		{
			System.out.println("ReuseForks is false, but should be OK");
//			reuseForks.setValue("true");
//			throw new MojoFailureException("reuseForks can't be false for now");
		}
		
		if(!isSetToFork)
		{
			forkCount = new Xpp3Dom("forkCount");
			forkCount.setValue("1");
			config.addChild(forkCount);
		}
	}

	private boolean shouldUseJunitCoreProvider(Xpp3Dom config)
	{
		boolean hasConcurrency = config.getChild("parallel") != null;
		boolean hasExclude = config.getChild("groups") != null || config.getChild("excludedGroups") != null;
		return hasConcurrency || hasExclude;
	}
	void injectConfig(Xpp3Dom config, boolean testNG, boolean forkPerTest) throws MojoFailureException {
		fixForkMode(config, forkPerTest);
	
		Xpp3Dom argLine = config.getChild("argLine");
		if(argLine == null)
		{
			argLine = new Xpp3Dom("argLine");
			argLine.setValue("");
			config.addChild(argLine);
		}
		if (argLine != null && argLine.getValue().equals("${argLine}"))
			argLine.setValue("'-XX:OnOutOfMemoryError=kill -9 %p' ");
		else if(argLine != null)
		{
			argLine.setValue("'-XX:OnOutOfMemoryError=kill -9 %p' " + argLine.getValue().replace("@{argLine}", "").replace("${argLine}", "").replace("${test.opts.coverage}", ""));
		}
		
		//Now fix if we wanted jacoco or cobertura
		if(argLine != null)
		{
			if(covType.startsWith("jacoco"))
			{
				argLine.setValue("@{argLine} " + argLine.getValue());
			}
		}
		
		if(!argLine.getValue().contains("-Xmx"))
			argLine.setValue(argLine.getValue() +" -Xmx2g");
		
		// Fork is either not present (default fork once, reuse), or is fork
		// once, reuse fork
		Xpp3Dom properties = config.getChild("properties");
		if (properties == null) {
			properties = new Xpp3Dom("properties");
			config.addChild(properties);
		}
		Xpp3Dom prop = new Xpp3Dom("property");
		properties.addChild(prop);
		Xpp3Dom propName = new Xpp3Dom("name");
		propName.setValue("listener");
		Xpp3Dom propValue = new Xpp3Dom("value");
		if(testNG)
			propValue.setValue("org.deflaker.listener.TestNGExecutionListener");
		else
			propValue.setValue("org.deflaker.listener.TestExecutionListener");

		prop.addChild(propName);
		prop.addChild(propValue);
		
		
		Xpp3Dom testFailureIgnore = config.getChild("testFailureIgnore");
		if(testFailureIgnore != null)
		{
			testFailureIgnore.setValue("true");
		}
		else
		{
			testFailureIgnore = new Xpp3Dom("testFailureIgnore");
			testFailureIgnore.setValue("true");
			config.addChild(testFailureIgnore);
		}
		
		Xpp3Dom vars = config.getChild("systemPropertyVariables");
		if(vars == null)
		{
			vars = new Xpp3Dom("systemPropertyVariables");
			config.addChild(vars);
		}
		Xpp3Dom log4jConfig = vars.getChild("log4j.configuration");
		if(log4jConfig == null)
		{
			log4jConfig = new Xpp3Dom("log4j.configuration");
			vars.addChild(log4jConfig);
		}
		log4jConfig.setValue("file:/fake-surefire-log4j.properties");
	}
	
	void injectConfigWithRerunsNoDiffcov(MavenProject p, Xpp3Dom config, boolean testNG, boolean forkPerTest, boolean isFailsafe) throws MojoFailureException {
		fixForkMode(config, forkPerTest);
	
		Xpp3Dom argLine = config.getChild("argLine");
		if(argLine == null)
		{
			argLine = new Xpp3Dom("argLine");
			argLine.setValue("");
			config.addChild(argLine);
		}
		if (argLine != null && argLine.getValue().equals("${argLine}"))
			argLine.setValue("'-XX:OnOutOfMemoryError=kill -9 %p' ");
		else if(argLine != null)
		{
			argLine.setValue("'-XX:OnOutOfMemoryError=kill -9 %p' " + argLine.getValue().replace("@{argLine}", "").replace("${argLine}", "").replace("${test.opts.coverage}", ""));
		}
		
		//Now fix if we wanted jacoco or cobertura
		if(argLine != null)
		{
			if(covType.startsWith("jacoco"))
			{
				argLine.setValue("@{argLine} " + argLine.getValue());
			}
		}
		
		if(!argLine.getValue().contains("-Xmx"))
			argLine.setValue(argLine.getValue() +" -Xmx2g");
		
		// Fork is either not present (default fork once, reuse), or is fork
		// once, reuse fork
		Xpp3Dom properties = config.getChild("properties");
		if (properties == null) {
			properties = new Xpp3Dom("properties");
			config.addChild(properties);
		}
		Xpp3Dom prop = new Xpp3Dom("property");
		properties.addChild(prop);
		Xpp3Dom propName = new Xpp3Dom("name");
		propName.setValue("listener");
		Xpp3Dom propValue = new Xpp3Dom("value");
		if(testNG)
			propValue.setValue("org.deflaker.listener.TestNGExecutionListener");
		else
			propValue.setValue("org.deflaker.listener.TestExecutionListener");

		prop.addChild(propName);
		prop.addChild(propValue);
		
		if(testNG)
		{
			Xpp3Dom threadCount = config.getChild("threadCount");
			if(threadCount == null)
			{
				threadCount = new Xpp3Dom("threadCount");
				config.addChild(threadCount);
			}
			threadCount.setValue("1");
		}
		{
			prop = new Xpp3Dom("property");
			properties.addChild(prop);
			propName = new Xpp3Dom("name");
			propName.setValue("builddir");
			propValue = new Xpp3Dom("value");
			propValue.setValue("${project.build.directory}");

			prop.addChild(propName);
			prop.addChild(propValue);
		}
		if(forkPerTest) //This is the exec to rerun failures
		{
			Xpp3Dom failIfNoTests = config.getChild("failIfNoTests");
			if(failIfNoTests == null)
			{
				failIfNoTests = new Xpp3Dom("failIfNoTests");
				config.addChild(failIfNoTests);
			}
			failIfNoTests.setValue("false");
			
			prop = new Xpp3Dom("property");
			properties.addChild(prop);
			propName = new Xpp3Dom("name");
			propName.setValue("rerunCount");
			propValue = new Xpp3Dom("value");
			propValue.setValue(new Integer(NUM_RERUNS).toString());

			prop.addChild(propName);
			prop.addChild(propValue);
			
			Xpp3Dom vars = config.getChild("systemPropertyVariables");
			if(vars == null)
			{
				vars = new Xpp3Dom("systemPropertyVariables");
				config.addChild(vars);
			}
			Xpp3Dom isInRerunFork = new Xpp3Dom("deflaker.isInRerunFork");
			vars.addChild(isInRerunFork);

			Xpp3Dom reportsDirectoryVar = new Xpp3Dom("deflaker.reportsDirectory");
			reportsDirectoryVar.setValue("${project.build.directory}/" + (isFailsafe ? "failsafe" : "surefire") + "-reports-isolated-reruns");
			//Make sure that this file gets deleted
			File reportFile = new File(p.getBuild().getOutputDirectory()+"/rerunResults");
			if(reportFile.exists())
				reportFile.delete();
			vars.addChild(reportsDirectoryVar);
			isInRerunFork.setValue("true");
			Xpp3Dom reportsDirectory  = config.getChild("reportsDirectory");
			if(reportsDirectory == null)
			{
				reportsDirectory = new Xpp3Dom("reportsDirectory");
				config.addChild(reportsDirectory);
			}
			reportsDirectory.setValue("${project.build.directory}/" + (isFailsafe ? "failsafe" : "surefire") + "-reports-isolated-reruns");
		}
		else
		{
			Xpp3Dom reruns = config.getChild("rerunFailingTestsCount");
			if(reruns == null)
			{
				reruns = new Xpp3Dom("rerunFailingTestsCount");
				config.addChild(reruns);
			}
			reruns.setValue(new Integer(NUM_RERUNS).toString());
		}
		Xpp3Dom testFailureIgnore = config.getChild("testFailureIgnore");
		if(testFailureIgnore != null)
		{
			testFailureIgnore.setValue("true");
		}
		else
		{
			testFailureIgnore = new Xpp3Dom("testFailureIgnore");
			testFailureIgnore.setValue("true");
			config.addChild(testFailureIgnore);
		}
		
		Xpp3Dom vars = config.getChild("systemPropertyVariables");
		if(vars == null)
		{
			vars = new Xpp3Dom("systemPropertyVariables");
			config.addChild(vars);
		}
		Xpp3Dom log4jConfig = vars.getChild("log4j.configuration");
		if(log4jConfig == null)
		{
			log4jConfig = new Xpp3Dom("log4j.configuration");
			vars.addChild(log4jConfig);
		}
		log4jConfig.setValue("file:/fake-surefire-log4j.properties");
	}
	
	void injectConfig(Xpp3Dom config, String pathToAgent, String pathToAgentBootpath, String gitDir, String outputFile, boolean testNG, boolean forkPerTest, boolean isFailsafe) throws MojoFailureException {
		fixForkMode(config, forkPerTest);
		
		
		// Fork is either not present (default fork once, reuse), or is fork
		// once, reuse fork
		Xpp3Dom argLine = config.getChild("argLine");
		if (argLine != null)
			argLine.setValue(argLine.getValue().replace("@{argLine}", "").replace("${argLine}", "").replace("${test.opts.coverage}", ""));
		String line = " '-XX:OnOutOfMemoryError=kill -9 %p' -Ddiffcov.log=" + outputFile + " -Xbootclasspath/p:" + pathToAgentBootpath + " -javaagent:" + pathToAgent + "=diffFile=" + diffFile;
		if("true".equals(System.getProperty("diffcov.allCoverage")))
			line += " -Ddiffcov.allCoverage=true";
		if("true".equals(System.getProperty("diffcov.onlyClass")))
			line += " -Ddiffcov.onlyClass=true";
		if (argLine != null && !argLine.getValue().equals("${argLine}"))
			argLine.setValue(argLine.getValue() + " " + line);
		else {
			argLine = new Xpp3Dom("argLine");
			argLine.setValue(line);
			config.addChild(argLine);
		}
		
		if(!argLine.getValue().contains("-Xmx"))
			argLine.setValue(argLine.getValue() +" -Xmx2g");
		Xpp3Dom properties = config.getChild("properties");
		if (properties == null) {
			properties = new Xpp3Dom("properties");
			config.addChild(properties);
		}
		Xpp3Dom prop = new Xpp3Dom("property");
		properties.addChild(prop);
		Xpp3Dom propName = new Xpp3Dom("name");
		propName.setValue("listener");
		Xpp3Dom propValue = new Xpp3Dom("value");
		if(testNG)
			propValue.setValue("org.deflaker.listener.TestNGListener");
		else
			propValue.setValue("org.deflaker.listener.CoverageListener");

		prop.addChild(propName);
		prop.addChild(propValue);
		
		if(testNG)
		{
			Xpp3Dom threadCount = config.getChild("threadCount");
			if(threadCount == null)
			{
				threadCount = new Xpp3Dom("threadCount");
				config.addChild(threadCount);
			}
			threadCount.setValue("1");
		}
//		if(LAZY_COV)
		{
			prop = new Xpp3Dom("property");
			properties.addChild(prop);
			propName = new Xpp3Dom("name");
			propName.setValue("builddir");
			propValue = new Xpp3Dom("value");
			propValue.setValue("${project.build.directory}");

			prop.addChild(propName);
			prop.addChild(propValue);
		}
		
		if(forkPerTest) //This is the exec to rerun failures
		{
			Xpp3Dom failIfNoTests = config.getChild("failIfNoTests");
			if(failIfNoTests == null)
			{
				failIfNoTests = new Xpp3Dom("failIfNoTests");
				config.addChild(failIfNoTests);
			}
			failIfNoTests.setValue("false");
			
			prop = new Xpp3Dom("property");
			properties.addChild(prop);
			propName = new Xpp3Dom("name");
			propName.setValue("rerunCount");
			propValue = new Xpp3Dom("value");
			propValue.setValue(new Integer(NUM_RERUNS).toString());

			prop.addChild(propName);
			prop.addChild(propValue);
			
			Xpp3Dom vars = config.getChild("systemPropertyVariables");
			if(vars == null)
			{
				vars = new Xpp3Dom("systemPropertyVariables");
				config.addChild(vars);
			}
			Xpp3Dom isInRerunFork = new Xpp3Dom("deflaker.isInRerunFork");
			vars.addChild(isInRerunFork);

			Xpp3Dom reportsDirectoryVar = new Xpp3Dom("deflaker.reportsDirectory");
			reportsDirectoryVar.setValue("${project.build.directory}/" + (isFailsafe ? "failsafe" : "surefire") + "-reports-isolated-reruns");
			vars.addChild(reportsDirectoryVar);
			isInRerunFork.setValue("true");
			Xpp3Dom reportsDirectory  = config.getChild("reportsDirectory");
			if(reportsDirectory == null)
			{
				reportsDirectory = new Xpp3Dom("reportsDirectory");
				config.addChild(reportsDirectory);
			}
			reportsDirectory.setValue("${project.build.directory}/" + (isFailsafe ? "failsafe" : "surefire") + "-reports-isolated-reruns");
		}
		else
		{
			Xpp3Dom reruns = config.getChild("rerunFailingTestsCount");
			if(reruns == null)
			{
				reruns = new Xpp3Dom("rerunFailingTestsCount");
				config.addChild(reruns);
			}
			reruns.setValue(new Integer(NUM_RERUNS).toString());
		}
		Xpp3Dom testFailureIgnore = config.getChild("testFailureIgnore");
		if(testFailureIgnore != null)
		{
			testFailureIgnore.setValue("true");
		}
		else
		{
			testFailureIgnore = new Xpp3Dom("testFailureIgnore");
			testFailureIgnore.setValue("true");
			config.addChild(testFailureIgnore);
		}
	}
}
