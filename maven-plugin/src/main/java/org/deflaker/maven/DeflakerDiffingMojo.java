package org.deflaker.maven;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactCollector;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.artifact.filter.ScopeArtifactFilter;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilder;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilderException;
import org.apache.maven.shared.dependency.tree.traversal.CollectingDependencyNodeVisitor;
import org.deflaker.diff.ClassInfo;
import org.deflaker.diff.ClassInfo.MethodInfo;
import org.deflaker.diff.Edit;
import org.deflaker.diff.LineAnalyzer;
import org.deflaker.diff.PreciseLineAnalyzer;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

@Mojo(name = "diff", defaultPhase = LifecyclePhase.VERIFY)
public class DeflakerDiffingMojo extends AbstractMojo {
	
	@Component
	private ArtifactFactory artifactFactory;
	@Component
	private ArtifactMetadataSource artifactMetadataSource;
	@Component
	private ArtifactCollector artifactCollector;
	@Component
	private DependencyTreeBuilder treeBuilder;

	@Component
	private ArtifactResolver resolver;

	@Component
	private MavenProject project;

	@Parameter(readonly = true, required = true)
	private String diffFile;

	@Parameter(readonly = true, required = true)
	private String gitDir;

	@Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
	private List<ArtifactRepository> remoteRepositories;
	@Parameter(defaultValue = "${localRepository}", readonly = true, required = true)
	private ArtifactRepository localRepository;

	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
	private List<MavenProject> reactorProjects;


//	private final static boolean PRECISE = System.getProperty("diffcov.precise") != null;
	public final static boolean NO_TYPE_RESOLUTION = System.getenv("diffcov.no_type_resolve") != null;

	HashSet<MavenProject> visited = new HashSet<MavenProject>();

	HashSet<String> dependenciesWithSourceDirs = new HashSet<String>();
	HashSet<String> dependenciesWithoutSourceDirs = new HashSet<String>();

	private static boolean equals(Artifact o1, Artifact o2) {
		return o1.getGroupId().equals(o2.getGroupId()) && o1.getArtifactId().equals(o2.getArtifactId()) && o1.getVersion().equals(o2.getVersion()) && o1.getType().equals(o2.getType());
	}

	private void collectDependencies(MavenProject project) throws DependencyTreeBuilderException {
		if (visited.contains(project))
			return;
		visited.add(project);

		dependenciesWithSourceDirs.add(project.getBuild().getOutputDirectory());
		dependenciesWithSourceDirs.add(project.getBuild().getTestOutputDirectory());

		ArtifactFilter artifactFilter = new ScopeArtifactFilter(null);

		DependencyNode rootNode = treeBuilder.buildDependencyTree(project, localRepository, artifactFactory, artifactMetadataSource, artifactFilter, artifactCollector);

		CollectingDependencyNodeVisitor visitor = new CollectingDependencyNodeVisitor();

		rootNode.accept(visitor);

		List<DependencyNode> nodes = visitor.getNodes();
		for (DependencyNode dependencyNode : nodes) {
			int state = dependencyNode.getState();
			Artifact artifact = dependencyNode.getArtifact();
			// Check to make sure that the artifact isn't part of the
			// reactor projects.
			boolean found = false;
			for (MavenProject p : reactorProjects) {
				if (equals(p.getArtifact(), artifact)) {
					// Ah-ha
					collectDependencies(p);
					found = true;
				}
			}
			if (found)
				continue;
			try {
				// System.out.println("Resolving " + artifact);
				// resolver.resolve(artifact, remoteRepositories,
				// localRepository);
				ArtifactResolutionResult res = resolver.resolveTransitively(Collections.singleton(artifact), project.getArtifact(), remoteRepositories, localRepository, artifactMetadataSource);
				for (Object r : res.getArtifacts()) {
					File f = ((Artifact) r).getFile();
					dependenciesWithSourceDirs.add(f.getAbsolutePath());
					dependenciesWithoutSourceDirs.add(f.getAbsolutePath());
				}
			} catch (ArtifactResolutionException e) {
//				if (debug)
					e.printStackTrace();
			} catch (ArtifactNotFoundException e) {
//				if (debug)
					e.printStackTrace();
			}
		}
	}
	class LazyDependencyResolver {
		String[] cpArray;

		public String[] getCP() throws MojoExecutionException {
			if (cpArray == null) {
				try {
					for(MavenProject p : reactorProjects)
						collectDependencies(p);
				} catch (DependencyTreeBuilderException e) {
					e.printStackTrace();
					throw new MojoExecutionException("", e);
				}

				cpArray = new String[dependenciesWithoutSourceDirs.size()];
				dependenciesWithoutSourceDirs.toArray(cpArray);
			}
			return cpArray;
		}
	}

	public static FirebaseLogger firebase;
	Log consoleLogger;

	PreparedStatement getCoverageClass;
	PreparedStatement insertCoverageClass;
	
	PreparedStatement getCoverageMethod;
	PreparedStatement insertCoverageMethod;
	
	PreparedStatement insertDiff;

	private HashMap<String, Integer> cachedClasses = new HashMap<String, Integer>();

	private int getCovClassId(String clazz) throws SQLException {
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
	public final static boolean ALL_COVERAGE = Boolean.valueOf(System.getProperty("diffcov.allCoverage", "false"));

	private Connection db;

	@SuppressWarnings("unchecked")
	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		consoleLogger = getLog();
		File diffFileFile = new File(diffFile);
		if (!diffFileFile.exists()) {
			int testExecId = -1;
			boolean toMysql = false;
			if(System.getProperty("diffcov.mysql") != null)
			{
				testExecId = Integer.valueOf(System.getProperty("diffcov.studyid"));
				toMysql = true;
				String classname = "com.mysql.jdbc.Driver";
				try {
					Driver d = (Driver) Class.forName(classname).newInstance();
					DriverManager.registerDriver(d);
					db = DriverManager.getConnection("jdbc:mysql://diffcov2017.c5smcgnslo73.us-east-1.rds.amazonaws.com/diffcov?user=diffcov&password=sqFycTgL35H5yegbe&useServerPrepStmts=false&rewriteBatchedStatements=true");
					getCoverageClass = db.prepareStatement("SELECT id FROM java_class WHERE name=?");
					insertCoverageClass = db.prepareStatement("INSERT INTO java_class (name) VALUES (?)", PreparedStatement.RETURN_GENERATED_KEYS);
					insertDiff = db.prepareStatement("INSERT INTO test_execution_diff (test_execution_id,java_class_id,statement,method,non_java) VALUES (?,?,?,?,?)");
					getCoverageMethod = db.prepareStatement("SELECT id FROM java_method WHERE name=?");
					insertCoverageMethod = db.prepareStatement("INSERT INTO java_method (name) VALUES (?)", PreparedStatement.RETURN_GENERATED_KEYS);

				} catch (SQLException | InstantiationException | ClassNotFoundException | IllegalAccessException ex) {
					ex.printStackTrace();
					throw new MojoFailureException(ex.getMessage());
				}
			}
			if(ALL_COVERAGE)
			{
				// We want to collect all of the java classes
				try {
					LinkedList<String> classes = new LinkedList<String>();
					Scanner s = new Scanner(new File(diffFileFile.getAbsolutePath() + ".builddirs"));
					while (s.hasNextLine()) {
						classes.addAll(collectJavaFiles(s.nextLine()));
					}
					s.close();
					File javaFile = new File(this.diffFile+".javafiles");
					if(javaFile.exists())
						javaFile.delete();

					FileWriter fw = new FileWriter(javaFile);
					for(String c : classes)
					{
						fw.write(c+"\n");
					}
					fw.close();
				} catch (IOException ex) {
					ex.printStackTrace();
				}
			}
			try {
				String commit =  System.getProperty("commit");
				if(commit == null)
					commit = System.getenv("diffcommit");
					if(commit == null)
						commit = "HEAD";
				System.out.println(gitDir + " DIFF'ing to " +  commit);
				if(System.getenv("TRAVIS") != null)
				{
					//Save to firebase
					if(firebase == null)
						firebase = new FirebaseLogger();
				}

				HashMap<String, ClassInfo> diffs;
				if(!NO_TYPE_RESOLUTION)
				{
					//Need to make sure there are no unresolved changes
//					LazyDependencyResolver r = new LazyDependencyResolver();
//					String[] cp = r.getCP();
//					System.out.println("Using CP: " + Arrays.toString(cp));
//					LinkedList<String> sp = new LinkedList<String>();
//					for(MavenProject p : reactorProjects)
//					{
//						if(Files.exists(Paths.get(p.getBuild().getSourceDirectory())))
//							sp.add(p.getBuild().getSourceDirectory());
//						if(Files.exists(Paths.get(p.getBuild().getTestSourceDirectory())))
//							sp.add(p.getBuild().getTestSourceDirectory());
//					}
//					String[] sps = new String[sp.size()];
//					sps = sp.toArray(sps);
					
//					System.out.println("Using SP: " + Arrays.toString(sps));
//					diffs = PreciseLineAnalyzer.getChanges(gitDir, commit, sps, cp);
					diffs = PreciseLineAnalyzer.getChanges(gitDir, commit, new String[0], new String[0]);

				}
				else
					diffs = LineAnalyzer.getChanges(gitDir, commit);
				boolean doInsert = false;
				for (String k : diffs.keySet()) {
					ClassInfo e = diffs.get(k);
					if(e == null)
					{
						if (toMysql) {
							int id = getCovClassId(k);
							doInsert = true;
							insertDiff.setInt(1, testExecId);
							insertDiff.setInt(2, id);
							insertDiff.setNull(3, Types.INTEGER);
							insertDiff.setNull(4, Types.INTEGER);
							insertDiff.setInt(5, 1);
							insertDiff.addBatch();
						}
						continue;
					}
					// System.out.println(e);
					if (firebase != null)
						firebase.log(e.className, e.hasStructuralProblems, e.edits);
					System.out.println(e.className + " " + (e.hasStructuralProblems ? "Structural diff" : "") + ": " + e.edits);
					if(!NO_TYPE_RESOLUTION && e.newMethods != null)
					{
						System.out.println("\t Methods to watch:"+e.newMethods);
					}
					// if(e.getChangeType() == ChangeType.MODIFY)
					// annotateSource(e);
					if (toMysql) {
						int id = getCovClassId(e.className);
						doInsert = true;
						if (e.hasStructuralProblems) {
							insertDiff.setInt(1, testExecId);
							insertDiff.setInt(2, id);
							insertDiff.setNull(3, Types.INTEGER);
							insertDiff.setNull(4, Types.INTEGER);
							insertDiff.setNull(5, Types.INTEGER);

							insertDiff.addBatch();
						} else {
							for (Edit ed : e.edits) {
								for (int i = ed.getEditStart(); i <= ed.getEditEnd(); i++) {
									insertDiff.setInt(1, testExecId);
									insertDiff.setInt(2, id);
									insertDiff.setInt(3, i);
									insertDiff.setNull(4, Types.INTEGER);
									insertDiff.setNull(5, Types.INTEGER);

									insertDiff.addBatch();
								}
							}
						}
						if (e.newMethods != null)
							for (MethodInfo i : e.newMethods) {
								insertDiff.setInt(1, testExecId);
								insertDiff.setInt(2, id);
								insertDiff.setNull(3, Types.INTEGER);
								insertDiff.setInt(4, getCovMethodId(i.name + i.desc));
								insertDiff.setNull(5, Types.INTEGER);
								insertDiff.addBatch();
							}
					}
				}
				if (doInsert)
					insertDiff.executeBatch();

				FileOutputStream fos = new FileOutputStream(diffFileFile);
				ObjectOutputStream oos = new ObjectOutputStream(fos);
				oos.writeObject(diffs);
				oos.close();
				if(firebase != null)
					firebase.awaitExit();
			} catch (Throwable t) {
				t.printStackTrace();
				throw new MojoFailureException("Couldn't calculate diff");
			}
		}
	}

	private static LinkedList<String> collectJavaFiles(String rootDir) {
		final LinkedList<String> ret = new LinkedList<String>();
		try {
			Files.walkFileTree(Paths.get(rootDir), new FileVisitor<Path>() {

				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (!file.toString().endsWith(".java"))
						return FileVisitResult.CONTINUE;
					ASTParser p = ASTParser.newParser(AST.JLS8);
					p.setBindingsRecovery(false);
					p.setResolveBindings(false);
					p.setUnitName("App.java");
					p.setEnvironment(new String[0], new String[0], new String[0], true);
					Map<String, String> options = JavaCore.getOptions();
					JavaCore.setComplianceOptions(JavaCore.VERSION_1_8, options);
					p.setCompilerOptions(options);
					p.setKind(ASTParser.K_COMPILATION_UNIT);
					Scanner sc = new Scanner(file);
					String content = sc.useDelimiter("\\Z").next();
					sc.close();
					p.setSource(content.toCharArray());
					final CompilationUnit root = (CompilationUnit) p.createAST(null);
					root.accept(new ASTVisitor() {
						String packageName;
						ClassInfo thisClass = null;

						@Override
						public void endVisit(TypeDeclaration node) {
							thisClass = thisClass.parent;
							super.endVisit(node);
						}

						@Override
						public boolean visit(AnonymousClassDeclaration node) {
							String name = thisClass.className + "$" + thisClass.anonCounter;
							thisClass.anonCounter++;
							ClassInfo newThisClass = new ClassInfo();
							newThisClass.className = name;
							ret.add(name);

							newThisClass.parent = thisClass;
							thisClass = newThisClass;

							return super.visit(node);
						}

						@Override
						public void endVisit(AnonymousClassDeclaration node) {
							thisClass = thisClass.parent;
							super.endVisit(node);
						}

						@Override
						public boolean visit(EnumDeclaration node) {
							String name = (packageName == null ? "" : packageName + ".") + node.getName().toString();
							if (thisClass == null) {
								thisClass = new ClassInfo();
								thisClass.edits = new LinkedList<Edit>();
								thisClass.className = name.replace('.', '/');
							}
							if (thisClass.className == null)
								thisClass.className = name.replace('.', '/');
							ret.add(thisClass.className);
							return super.visit(node);
						}

						@Override
						public boolean visit(PackageDeclaration node) {
							packageName = node.getName().getFullyQualifiedName();
							return super.visit(node);
						}

						@Override
						public boolean visit(TypeDeclaration node) {
							String name = (packageName == null ? "" : packageName + ".") + node.getName().toString();
							name = name.replace('.', '/');
							if (thisClass == null) {
								// Root element
								thisClass = new ClassInfo();
								thisClass.className = name;
							}
							if (thisClass.className != null && !thisClass.className.equals(name)) {
								ClassInfo newThisClass = new ClassInfo();
								name = thisClass.className + "$" + node.getName().toString();
								newThisClass.className = name;
								thisClass.innerClasses.add(newThisClass);
								newThisClass.parent = thisClass;
								thisClass = newThisClass;
							}
							if (node.getSuperclassType() != null)
								thisClass.superName = node.getSuperclassType().toString();
							thisClass.className = name;
							ret.add(thisClass.className);
							return super.visit(node);
						}
					});
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
		return ret;
	}

	public static void main(String[] args) {
		System.out.println(collectJavaFiles("/Users/jon/Documents/GMU/Projects/surefire-diff-coverage/experiments/hbc/hbc-core/src/main/java"));
	}
}
