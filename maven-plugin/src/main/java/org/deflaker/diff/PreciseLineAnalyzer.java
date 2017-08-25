package org.deflaker.diff;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Modifier;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.deflaker.diff.ClassInfo.MethodInfo;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

public class PreciseLineAnalyzer {
	public static boolean PRINT_DEBUG = System.getenv("DIFFCOV_DEBUG") != null;
	
	private static int version_id;
	private static boolean LOG_VERBOSE_MYSQL;
	private static Connection db;
	private static PreparedStatement getCoverageClass;
	private static PreparedStatement getCoverageMethod;
	private static PreparedStatement insertCoverageClass;
	private static PreparedStatement insertCoverageMethod;
	private static PreparedStatement insertDiff;
	
	private static HashMap<String, Integer> cachedClasses = new HashMap<String, Integer>();

	private static int getCovClassId(String clazz) throws SQLException {
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
	
	private static int getCovNameId(String clazz) throws SQLException {
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


	private static  HashMap<String, Integer> cachedMethods = new HashMap<String, Integer>();
	private static int getCovMethodId(String meth) throws SQLException
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
	public static void main(String[] args) throws CorruptObjectException, MissingObjectException, IOException {
		if(args.length == 3)
		{
			version_id=Integer.valueOf(args[0]);
			LOG_VERBOSE_MYSQL = true;			
		}
		HashMap<String, ClassInfo> edits = null;
		if(LOG_VERBOSE_MYSQL)
		{
			String classname = "com.mysql.jdbc.Driver";
			try {
				Driver d = (Driver) Class.forName(classname).newInstance();
				DriverManager.registerDriver(d);
				db = DriverManager.getConnection("jdbc:mysql://diffcov2017.c5smcgnslo73.us-east-1.rds.amazonaws.com/diffcov?user=diffcov&password=sqFycTgL35H5yegbe&useServerPrepStmts=false&rewriteBatchedStatements=true");
				getCoverageClass = db.prepareStatement("SELECT id FROM java_class WHERE name=?");
				insertCoverageClass = db.prepareStatement("INSERT INTO java_class (name) VALUES (?)", PreparedStatement.RETURN_GENERATED_KEYS);
				insertDiff = db.prepareStatement("INSERT INTO evo_project_version_diff (version_id,java_class_id,statement_start,method,non_java,method_added,method_removed,class_added,super_changed,statement_end,is_backup) VALUES (?,?,?,?,?,?,?,?,?,?,?)");
				getCoverageMethod = db.prepareStatement("SELECT id FROM java_method WHERE name=?");
				insertCoverageMethod = db.prepareStatement("INSERT INTO java_method (name) VALUES (?)", PreparedStatement.RETURN_GENERATED_KEYS);

			} catch (SQLException | InstantiationException | ClassNotFoundException | IllegalAccessException ex) {
				ex.printStackTrace();
			}
			edits = getChanges(args[1], args[2], new String[0], new String[0]);
		}
		else{
			String[] sourcePath = new String[]{"/Users/jon/Documents/GMU/Projects/surefire-diff-coverage/experiments/canaryTravisRepo/src/main/java",
					"/Users/jon/Documents/GMU/Projects/surefire-diff-coverage/experiments/canaryTravisRepo/src/test/java"};
			String[] cp = {};
	//		String[] cp = {"/Users/jon/.m2/repository/edu/gmu/swe/surefire/diff-coverage-listener/1.1-SNAPSHOT/diff-coverage-listener-1.1-SNAPSHOT.jar" , "/Users/jon/.m2/repository/junit/junit/4.12/junit-4.12.jar" , "/Users/jon/.m2/repository/org/hamcrest/hamcrest-core/1.1/hamcrest-core-1.1.jar" , "/Users/jon/.m2/repository/com/github/marschall/memoryfilesystem/0.6.4/memoryfilesystem-0.6.4.jar" , "/Users/jon/.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar" , "/Users/jon/.m2/repository/cglib/cglib-nodep/2.2.2/cglib-nodep-2.2.2.jar" , "/Users/jon/.m2/repository/org/mockito/mockito-core/1.9.5/mockito-core-1.9.5.jar" , "/Users/jon/.m2/repository/org/objenesis/objenesis/1.0/objenesis-1.0.jar" , "/Users/jon/.m2/repository/edu/gmu/swe/surefire/diff-coverage-agent-bootpath/1.1-SNAPSHOT/diff-coverage-agent-bootpath-1.1-SNAPSHOT.jar"};
	//		String[] sourcePath = {"/Users/jon/Documents/GMU/Projects/surefire-diff-coverage/experiments/assertj-core/src/main/java" , "/Users/jon/Documents/GMU/Projects/surefire-diff-coverage/experiments/assertj-core/src/test/java"};
	//		HashMap<String, ClassInfo> edits = getChanges("/Users/jon/Documents/GMU/Projects/surefire-diff-coverage/experiments/assertj-core/.git", "head", sourcePath, cp);
	//		HashMap<String, ClassInfo> edits = getChanges("/Users/jon/Documents/GMU/Projects/surefire-diff-coverage/experiments/okhttp/.git", "d173c9e908a8ce1e9e02e79d16e12aa2905d724f", sourcePath, cp);
//			edits = getChanges("/Users/jon/Documents/GMU/Projects/surefire-diff-coverage/experiments/twilio-java/.git", "head", sourcePath, cp);
			edits = getChanges("/Users/jon/Documents/GMU/Projects/surefire-diff-coverage/experiments/canaryTravisRepo/.git", "head", sourcePath, cp);
		}
		for (ClassInfo e : edits.values()) {
//			System.out.println(e);
			System.out.println(e.className + e.startLine+"-"+e.endLine+" "+ (e.hasStructuralProblems)+": " + e.edits +", " + e.newMethods);
			if(LOG_VERBOSE_MYSQL)
			{
				try{
					int cid = getCovClassId(e.className);
					for(Edit ed : e.edits)
					{
						insertDiff.setInt(1, version_id);
						insertDiff.setInt(2, cid);
						insertDiff.setInt(3, (e.startLine > ed.editStart ? e.startLine : ed.editStart));
						insertDiff.setInt(4, 0);
						insertDiff.setInt(5, 0);
						insertDiff.setInt(6, 0);
						insertDiff.setInt(7, 0);
						insertDiff.setInt(8, 0);
						insertDiff.setInt(9, 0);
						insertDiff.setInt(10, (e.endLine > 0 && e.endLine < ed.editEnd ? e.endLine : ed.editEnd));
						insertDiff.setInt(11, 0);
						insertDiff.addBatch();
					}
					if(e.hasStructuralProblems)
					{
						insertDiff.setInt(1, version_id);
						insertDiff.setInt(2, cid);
						insertDiff.setInt(3, 0);
						insertDiff.setInt(4, 0);
						insertDiff.setInt(5, 0);
						insertDiff.setInt(6, 0);
						insertDiff.setInt(7, 0);
						insertDiff.setInt(8, 0);
						insertDiff.setInt(9, 0);
						insertDiff.setInt(10, 0);
						insertDiff.setInt(11, 0);
						insertDiff.addBatch();
					} else {
						insertDiff.setInt(1, version_id);
						insertDiff.setInt(2, cid);
						insertDiff.setInt(3, 0);
						insertDiff.setInt(4, 0);
						insertDiff.setInt(5, 0);
						insertDiff.setInt(6, 0);
						insertDiff.setInt(7, 0);
						insertDiff.setInt(8, 0);
						insertDiff.setInt(9, 0);
						insertDiff.setInt(10, 0);
						insertDiff.setInt(11, 1);
						insertDiff.addBatch();
					}
					
				}
				catch(SQLException ex)
				{
					ex.printStackTrace();
				}
			}
			// if(e.getChangeType() == ChangeType.MODIFY)
//			annotateSource(e);
		}
		if(LOG_VERBOSE_MYSQL)
		{
			try {
				insertDiff.executeBatch();
			} catch (SQLException e1) {
				e1.printStackTrace();
			}
		}
	}
	static boolean isEdited(LinkedList<Edit> edits, CompilationUnit root, ASTNode node) {
		int startLine = root.getLineNumber(node.getStartPosition());
		if (node instanceof BodyDeclaration && ((BodyDeclaration) node).getJavadoc() != null)
			startLine = root.getLineNumber(node.getStartPosition() + ((BodyDeclaration) node).getJavadoc().getLength() + 1);
		int endLine = root.getLineNumber(node.getStartPosition() + node.getLength());
		for (Edit e : edits)
			if (e.isInRange(startLine, endLine))
				return true;
		return false;
	}
	public static void collectStructuralElements(final EditedFile e, byte[] src, final LinkedList<Edit> edits, final boolean isNew, String[] sourcePath, String[] cp) throws FileNotFoundException, UnsupportedEncodingException {

		ASTParser p = ASTParser.newParser(AST.JLS8);
//		p.setBindingsRecovery(true);
//		p.setResolveBindings(true);
		
		p.setUnitName(new File(e.fileName).getName());
		String[] encodings = new String[sourcePath.length];
		for (int i = 0; i < sourcePath.length; i++)
			encodings[i] = "UTF-8";
		p.setEnvironment(cp, sourcePath, encodings, true);
		Map<String, String> options = JavaCore.getOptions();
		JavaCore.setComplianceOptions(JavaCore.VERSION_1_8, options);
		p.setCompilerOptions(options);
		p.setKind(ASTParser.K_COMPILATION_UNIT);

		p.setSource(new String(src,"UTF-8").toCharArray()); //TODO fix char encodings
		final CompilationUnit root = (CompilationUnit) p.createAST(null);
		if(PRINT_DEBUG)
			for (IProblem prob : root.getProblems()) {
				System.err.println("Problem: " + prob);
			}

		final HashSet<String> ret = new HashSet<String>();
//		ASTRewrite rewriter = ASTRewrite.create(root.getAST());

		root.accept(new ASTVisitor() {
			ClassInfo thisClass = null;
			HashSet<String> constructors = new HashSet<String>();
			boolean constructorsRelevant;

			String packageName;
			@Override
			public boolean visit(PackageDeclaration node) {
				packageName = node.getName().getFullyQualifiedName();
				return super.visit(node);
			}
			@Override
			public void endVisit(CompilationUnit node) {
				if (constructorsRelevant)
					ret.addAll(constructors);
			}

			@Override
			public void endVisit(TypeDeclaration node) {
				thisClass = thisClass.parent;
				super.endVisit(node);
			}
			@Override
			public void endVisit(EnumDeclaration node) {
				thisClass = thisClass.parent;
				super.endVisit(node);
			}
			
//			@Override
//			public boolean visit(LambdaExpression node) {
//				String name = thisClass.className + "$$Lambda$"+thisClass.lambdaCounter;
//				thisClass.lambdaCounter++;
//				ClassInfo newThisClass = new ClassInfo();
//				newThisClass.className = name;
//				newThisClass.parent = thisClass;
//				thisClass = newThisClass;
//				LinkedList<Edit> editsThisType = new LinkedList<Edit>();
//				int startLine = root.getLineNumber(node.getStartPosition());
//				int endLine = root.getLineNumber(node.getStartPosition() + node.getLength());
//				for(Edit e : edits)
//				{
//					if(e.editStart >= startLine && e.editEnd <= endLine)
//						editsThisType.add(e);
//				}
//				thisClass.startLine = root.getLineNumber(node.getStartPosition());
//				thisClass.endLine = root.getLineNumber(node.getStartPosition()+node.getLength());
//				thisClass.edits = editsThisType;
//				thisClass.filterEditsFromParents();
//				if (isNew)
//					e.newClasses.put(name.replace('.', '/'), thisClass);
//				else
//					e.oldClasses.put(name.replace('.', '/'), thisClass);
//				return super.visit(node);
//			}
//			@Override
//			public void endVisit(LambdaExpression node) {
//				thisClass = thisClass.parent;
//				super.endVisit(node);
//				}
			@Override
			public boolean visit(AnonymousClassDeclaration node) {
				String name = thisClass.className + "$"+thisClass.anonCounter;
				thisClass.anonCounter++;
				ClassInfo newThisClass = new ClassInfo();
				newThisClass.className = name;
				newThisClass.parent = thisClass;
				thisClass = newThisClass;
				LinkedList<Edit> editsThisType = new LinkedList<Edit>();
				int startLine = root.getLineNumber(node.getStartPosition());
				int endLine = root.getLineNumber(node.getStartPosition() + node.getLength());
				for(Edit e : edits)
				{
					if(e.editStart >= startLine && e.editEnd <= endLine)
						editsThisType.add(e);
				}
				thisClass.startLine = root.getLineNumber(node.getStartPosition());
				thisClass.endLine = root.getLineNumber(node.getStartPosition()+node.getLength());
				thisClass.edits = editsThisType;
				thisClass.filterEditsFromParents();
				if (isNew)
					e.newClasses.put(name.replace('.', '/'), thisClass);
				else
					e.oldClasses.put(name.replace('.', '/'), thisClass);

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
				if(thisClass == null)
				{
					thisClass = new ClassInfo();
					thisClass.edits = new LinkedList<Edit>();
					thisClass.className = name.replace('.', '/');
					if (isNew)
						e.newClasses.put(name.replace('.', '/'), thisClass);
					else
						e.oldClasses.put(name.replace('.', '/'), thisClass);
				}
				if(thisClass.className != null && !thisClass.className.equals(name))
				{
					ClassInfo newThisClass = new ClassInfo();
					name = thisClass.className+"$"+node.getName().toString();
					newThisClass.className = name;
					thisClass.innerClasses.add(newThisClass);
					newThisClass.parent = thisClass;
					thisClass = newThisClass;
					LinkedList<Edit> editsThisType = new LinkedList<Edit>();
					int startLine = root.getLineNumber(node.getStartPosition());
					int endLine = root.getLineNumber(node.getStartPosition() + node.getLength());
					for(Edit e : edits)
					{
						if(e.isInRange(startLine, endLine))
							editsThisType.add(e);
					}
					thisClass.edits = editsThisType;
					thisClass.startLine = root.getLineNumber(node.getStartPosition());
					thisClass.endLine = root.getLineNumber(node.getStartPosition()+node.getLength());
					thisClass.filterEditsFromParents();
					if (isNew)
						e.newClasses.put(name.replace('.', '/'), thisClass);
					else
						e.oldClasses.put(name.replace('.', '/'), thisClass);
				}
				else if(thisClass.className == null)
					thisClass.className= name.replace('.', '/');
				thisClass.startLine = root.getLineNumber(node.getStartPosition());
				thisClass.endLine = root.getLineNumber(node.getStartPosition()+node.getLength());
				return super.visit(node);
			}
			
			String toDesc(String binaryName){
				if(binaryName.length() == 1)
					return binaryName;
				else if(binaryName.charAt(0) == '[')
					return binaryName.replace('.', '/');// +";";
				else
					return "L" + binaryName.replace('.', '/') +";";
			}
			HashSet<MethodInfo> getSuperMethods(ITypeBinding node)
			{
				HashSet<MethodInfo> ret =new HashSet<MethodInfo>();
				for(IMethodBinding b : node.getDeclaredMethods())
				{
					if(Modifier.isAbstract(b.getModifiers()))
						continue;
					if(Modifier.isPrivate(b.getModifiers()))
						continue;
					
					StringBuilder fq = new StringBuilder();

					String name;
					if (b.isConstructor())
						name = "<init>";
					else
						name = b.getName();
					fq.append('(');
					for (ITypeBinding p : b.getParameterTypes()) {
						fq.append(toDesc(p.getBinaryName()));
					}
					fq.append(')');
					if (b.isConstructor())
						fq.append('V');
					else {
						fq.append(toDesc(b.getReturnType().getBinaryName()));
					}
					
					ret.add(new MethodInfo(fq.toString(), name, node.getBinaryName().replace('.', '/')));
				}
				ITypeBinding superT = node.getSuperclass();
				if(superT != null)
					ret.addAll(getSuperMethods(superT));

				return ret;
			}
			/*
			HashSet<FieldInfo> getSuperFields(ITypeBinding node)
			{
				HashSet<FieldInfo> ret =new HashSet<FieldInfo>();
				for(IVariableBinding b : node.getDeclaredFields())
				{
					if(Modifier.isAbstract(b.getModifiers()))
						continue;
					if(Modifier.isPrivate(b.getModifiers()))
						continue;
					
					StringBuilder fq = new StringBuilder();

					String name;
					FieldInfo fi = new FieldInfo();
					fi.name = node.getName();
					ret.add(fi);
				}
				ITypeBinding superT = node.getSuperclass();
				if(superT != null)
					ret.addAll(getSuperFields(superT));

				return ret;
			}*/

			@Override
			public boolean visit(AnnotationTypeDeclaration node) {
				String name = (packageName == null ? "" : packageName + ".") + node.getName().toString();
				name = name.replace('.', '/');
				if (thisClass == null) {
					// Root element
					thisClass = new ClassInfo();
					thisClass.edits = new LinkedList<Edit>(edits);
					thisClass.className = name;
					if (isNew)
						e.newClasses.put(name.replace('.', '/'), thisClass);
					else
						e.oldClasses.put(name.replace('.', '/'), thisClass);
					thisClass.startLine = root.getLineNumber(node.getStartPosition());
					thisClass.endLine = root.getLineNumber(node.getStartPosition()+node.getLength());
				}
				return super.visit(node);
			}
			
			@Override
			public boolean visit(TypeDeclaration node) {
				String name = (packageName == null ? "" : packageName + ".") + node.getName().toString();
				name = name.replace('.', '/');
				if (thisClass == null) {
					// Root element
					thisClass = new ClassInfo();
					thisClass.edits = new LinkedList<Edit>(edits);
					thisClass.className = name;
					if (isNew)
						e.newClasses.put(name.replace('.', '/'), thisClass);
					else
						e.oldClasses.put(name.replace('.', '/'), thisClass);
					thisClass.startLine = root.getLineNumber(node.getStartPosition());
					thisClass.endLine = root.getLineNumber(node.getStartPosition()+node.getLength());
				}
				if(thisClass.className != null && !thisClass.className.equals(name))
				{
					ClassInfo newThisClass = new ClassInfo();
					name = thisClass.className+"$"+node.getName().toString();
					newThisClass.className = name;
					thisClass.innerClasses.add(newThisClass);
					newThisClass.parent = thisClass;
					thisClass = newThisClass;
					LinkedList<Edit> editsThisType = new LinkedList<Edit>();
					int startLine = root.getLineNumber(node.getStartPosition());
					int endLine = root.getLineNumber(node.getStartPosition() + node.getLength());
					for(Edit e : edits)
					{
						if(e.editStart >= startLine && e.editEnd <= endLine)
							editsThisType.add(e);
					}
					thisClass.edits = editsThisType;
					thisClass.startLine = root.getLineNumber(node.getStartPosition());
					thisClass.endLine = root.getLineNumber(node.getStartPosition()+node.getLength());
					thisClass.filterEditsFromParents();
					if (isNew)
						e.newClasses.put(name.replace('.', '/'), thisClass);
					else
						e.oldClasses.put(name.replace('.', '/'), thisClass);
				}
				if(node.getSuperclassType() != null)
				{
					thisClass.superName = node.getSuperclassType().toString();
					if (isNew) {
						ITypeBinding superT = node.getSuperclassType().resolveBinding();
						if(superT != null)
						{
							thisClass.superMethods = getSuperMethods(superT);
//							thisClass.superFields = getSuperFields(superT);
						}
//						System.out.println(thisClass.superMethods);
					}
				}
				thisClass.className = name;
				return super.visit(node);
			}
			private boolean visitAnnotation(Annotation node)
			{
				if (isEdited(edits, root, node)) {
					if (thisClass != null) {
						if (node.getParent() instanceof MethodDeclaration) {
							thisClass.methodsWithChangedAnnotations.add(toMethodInfo((MethodDeclaration) node.getParent()));
						} else
							thisClass.hasEditedAnnotation = true;
					}
				}
				return true;
			}
			@Override
			public boolean visit(MarkerAnnotation node) {
				return visitAnnotation(node);
			}
			@Override
			public boolean visit(SingleMemberAnnotation node) {
				return visitAnnotation(node);
			}
			@Override
			public boolean visit(NormalAnnotation node) {
				return visitAnnotation(node);
			}
			@Override
			public boolean visit(FieldDeclaration node) {
				for(Object c : node.fragments())
				{
					if(c instanceof VariableDeclarationFragment)
					{
						VariableDeclarationFragment f = (VariableDeclarationFragment) c;
						Expression initializer = f.getInitializer();
						FieldInfo fi = new FieldInfo();
						fi.name = f.getName().toString();
						fi.desc = node.getType().toString();
						if(initializer != null)
							fi.init = initializer.toString();
						thisClass.fields.add(fi);
					}

				}
				return true;
			}
			private MethodInfo toMethodInfo(MethodDeclaration node)
			{
				StringBuffer fq = new StringBuffer();
				String name;
				if (node.isConstructor())
					name = "<init>";
				else
					name = node.getName().toString();
				fq.append('(');
				boolean hasParams = false;
				for (Object p : node.parameters()) {
					SingleVariableDeclaration d = (SingleVariableDeclaration) p;
					ITypeBinding b = d.getType().resolveBinding();
					if(b != null && b.getBinaryName() != null)
						fq.append(toDesc(b.getBinaryName()));
					else
						fq.append(toDesc(d.getType().toString()));
					hasParams = true;
				}
				if (hasParams) {
					fq.deleteCharAt(fq.length() - 1);
				}
				fq.append(')');
				if (node.isConstructor())
					fq.append('V');
				else {
					ITypeBinding b = node.getReturnType2().resolveBinding();
					if(b != null && b.getBinaryName() != null)
						fq.append(toDesc(b.getBinaryName()));
					else
						fq.append(toDesc(node.getReturnType2().toString()));
				}
				return new MethodInfo(fq.toString(), name, thisClass.className);
			}
			@Override
			public boolean visit(MethodDeclaration node) {
				thisClass.methods.add(toMethodInfo(node));
//				System.out.println(node);
				return true;
			}

		});
	}

	public static HashMap<String, ClassInfo> getChanges(String gitDir, String _commit, String[] sourcePath, String[] cp) throws CorruptObjectException, MissingObjectException, IOException {
		RevWalk revWalk = null;
		HashMap<String, ClassInfo> ret = new HashMap<String, ClassInfo>();
		HashMap<String, HashSet<MethodInfo>> methodsToTrack = new HashMap<String, HashSet<MethodInfo>>();
		try {
			Repository repo = new FileRepositoryBuilder().setGitDir(new File(gitDir)).build();
			ObjectId com = repo.resolve(_commit);
			revWalk = new RevWalk(repo);
			RevCommit commit = revWalk.parseCommit(com);

			CanonicalTreeParser thisCommParser = new CanonicalTreeParser();
			ObjectReader reader = repo.newObjectReader();
			RevCommit toDiff = null;
			if(System.getProperty("diffcov.parentCommit") != null)
			{
				ObjectId d= repo.resolve(System.getProperty("diffcov.parentCommit"));
				toDiff = revWalk.parseCommit(d);
			}
			if(toDiff == null)
			{
				//Diff against parent.
				toDiff = commit.getParent(0);
			}
			RevCommit p = toDiff;
			{
				System.out.println("Diff'ing "+commit.name()+" against: " + p.getName());
				thisCommParser.reset(reader, commit.getTree());
				p = revWalk.parseCommit(p.getId());
				CanonicalTreeParser parentParser = new CanonicalTreeParser();
				parentParser.reset(reader, p.getTree());
				DiffFormatter f = new DiffFormatter(System.out);
				f.setRepository(repo);
				List<DiffEntry> entries = f.scan(parentParser, thisCommParser);
				for (DiffEntry diff : entries) {
					// changenew
					// FileChange(diff,f.toFileHeader(diff).toEditList(),p.getId().name(),_commit);
					if(diff.getChangeType() == ChangeType.ADD && diff.getNewPath().endsWith(".java"))
					{
						EditedFile ef = new EditedFile();
						ef.fileName= Paths.get(gitDir, "../", diff.getNewPath()).toString();
						AbbreviatedObjectId newId = diff.getNewId();
						ObjectLoader loader = repo.open(newId.toObjectId());
						LinkedList<Edit> edits = new LinkedList<Edit>();
						//We need to look for structural changes to the file
						collectStructuralElements(ef, loader.getBytes(), edits, true, sourcePath, cp);
//						System.out.println(ef.newClasses);
						for(ClassInfo ci : ef.newClasses.values())
						{
							ci.hasStructuralProblems = true;
							ret.put(ci.className.replace('.', '/'),ci);
							if (insertDiff != null)
								try {
									insertDiff.setInt(1, version_id);
									insertDiff.setInt(2, getCovClassId(ci.className));
									insertDiff.setInt(3, 0);
									insertDiff.setInt(4, 0);
									insertDiff.setInt(5, 0);
									insertDiff.setInt(6, 0);
									insertDiff.setInt(7, 0);
									insertDiff.setInt(8, 1);
									insertDiff.setInt(9, 0);
									insertDiff.setInt(10, 0);
									insertDiff.setInt(11, 0);

									insertDiff.addBatch();
								} catch (SQLException ex) {
									ex.printStackTrace();
								}
						}
					}
					else if ((diff.getNewPath() != null && diff.getNewPath().endsWith(".java")) || (diff.getOldPath() != null && diff.getOldPath().endsWith(".java"))) {
						EditList el = f.toFileHeader(diff).toEditList();
						String filePath = Paths.get(gitDir, "../", diff.getNewPath()).toString();
						LinkedList<Edit> edits = new LinkedList<Edit>();
						for (org.eclipse.jgit.diff.Edit e : el) {
							edits.add(new Edit(e.getBeginB()+1, e.getEndB()));
						}
						EditedFile ef = new EditedFile();
						ef.fileName = filePath;
						if(diff.getChangeType() == ChangeType.MODIFY)
						{
							AbbreviatedObjectId newId = diff.getNewId();
							ObjectLoader loader = repo.open(newId.toObjectId());
							//We need to look for structural changes to the file
							collectStructuralElements(ef, loader.getBytes(), edits, true, sourcePath, cp);
							
							edits = new LinkedList<Edit>();
							for (org.eclipse.jgit.diff.Edit e : el) {
								if(e.getEndA() != e.getBeginA())
									edits.add(new Edit(e.getBeginA()+1, e.getEndA()));
							}
							AbbreviatedObjectId oldId = diff.getOldId();
							loader = repo.open(oldId.toObjectId());
							collectStructuralElements(ef, loader.getBytes(), edits, false, sourcePath, cp);
							for(String name : ef.newClasses.keySet())
							{
								ClassInfo newCI = ef.newClasses.get(name);
								ClassInfo old = ef.oldClasses.get(name);
								if(old == null)
								{
									if (insertDiff != null)
										try {
											insertDiff.setInt(1, version_id);
											insertDiff.setInt(2, getCovClassId(newCI.className));
											insertDiff.setInt(3, 0);
											insertDiff.setInt(4, 0);
											insertDiff.setInt(5, 0);
											insertDiff.setInt(6, 0);
											insertDiff.setInt(7, 0);
											insertDiff.setInt(8, 1);
											insertDiff.setInt(9, 0);
											insertDiff.setInt(10, 0);
											insertDiff.setInt(11, 0);

											insertDiff.addBatch();
										} catch (SQLException ex) {
											ex.printStackTrace();
										}
									newCI.hasStructuralProblems = true;
								}
								else {
									if(!equals(newCI.superName,old.superName))
									{
										if (insertDiff != null)
											try {
												insertDiff.setInt(1, version_id);
												insertDiff.setInt(2, getCovClassId(newCI.className));
												insertDiff.setInt(3, 0);
												insertDiff.setInt(4, 0);
												insertDiff.setInt(5, 0);
												insertDiff.setInt(6, 0);
												insertDiff.setInt(7, 0);
												insertDiff.setInt(8, 0);
												insertDiff.setInt(9, 1);
												insertDiff.setInt(10, 0);
												insertDiff.setInt(11, 0);

												insertDiff.addBatch();
											} catch (SQLException ex) {
											ex.printStackTrace();
										}
									}
//									if (newCI.hasEditedAnnotation)
//										newCI.hasStructuralProblems = true;
									HashSet<MethodInfo> newMethods = new HashSet<MethodInfo>(newCI.methods);
									newMethods.removeAll(old.methods);

									HashSet<MethodInfo> removedMethods = new HashSet<MethodInfo>(old.methods);
									removedMethods.removeAll(newCI.methods);

									newCI.newMethods = new ArrayList<ClassInfo.MethodInfo>(); //No need to actually track all new methods - just the super override ones.
									newCI.methodsWithChangedAnnotations.removeAll(newMethods);
//									if (!newCI.methodsWithChangedAnnotations.isEmpty())
//										newCI.hasStructuralProblems = true;

									old.methodsWithChangedAnnotations.removeAll(removedMethods);
									old.methodsWithChangedAnnotations.removeAll(newMethods);

//									if (!old.methodsWithChangedAnnotations.isEmpty())
//									{
//										System.out.println("Annotation problem on " + newCI.className + " forcing class coverage");
//										newCI.hasStructuralProblems = true;
//									}

									if (newCI.equalsWithoutMethodsOrFields(old) && !newCI.equals(old)) {
										if (PRINT_DEBUG) 
										{
											System.out.println("Examining " + newCI.className);
											System.out.println("New methods: " + newMethods);
											System.out.println("Removed methods: " + removedMethods);
											System.out.println("Parent methods: " + newCI.superMethods);
										}
										if(LOG_VERBOSE_MYSQL)
										{
											for(MethodInfo s : newMethods)
											{
												try{
													insertDiff.setInt(1, version_id);
													insertDiff.setInt(2, getCovClassId(newCI.className));
													insertDiff.setInt(3, 0);
													insertDiff.setInt(4, getCovMethodId(s.name+s.desc));
													insertDiff.setInt(5, 0);
													insertDiff.setInt(6, 1);
													insertDiff.setInt(7, 0);
													insertDiff.setInt(8, 0);
													insertDiff.setInt(9, 0);
													insertDiff.setInt(10, 0);
													insertDiff.setInt(11, 0);

													
													insertDiff.addBatch();
												}
												catch(SQLException ex)
												{
													ex.printStackTrace();
												}
											}
											for(MethodInfo s : removedMethods)
											{
												try{
													insertDiff.setInt(1, version_id);
													insertDiff.setInt(2, getCovClassId(newCI.className));
													insertDiff.setInt(3, 0);
													insertDiff.setInt(4, getCovMethodId(s.name+s.desc));
													insertDiff.setInt(5, 0);
													insertDiff.setInt(6, 0);
													insertDiff.setInt(7, 1);
													insertDiff.setInt(8, 0);
													insertDiff.setInt(9, 0);
													insertDiff.setInt(10, 0);
													insertDiff.setInt(11, 0);

													insertDiff.addBatch();
												}
												catch(SQLException ex)
												{
													ex.printStackTrace();
												}
											}
										}
//										System.out.println("Parent fields: " + newCI.superFields);
										// TODO do something w fields

										// Check and see if there is a
										// difference in methods, find what it
										// is.
										if (!removedMethods.isEmpty() && newCI.superMethods == null && newCI.superName != null) {
//											newCI.hasStructuralProblems = true;
											//Now that will get handled by the catch-all "backup" coverage
										} else if (newCI.superName != null)
											for (MethodInfo s : removedMethods) {
												for (MethodInfo m : newCI.superMethods) {
													if (s.name.equals(m.name) && s.desc.equals(m.desc)) {
														if (!methodsToTrack.containsKey(m.owner))
															methodsToTrack.put(m.owner, new HashSet<ClassInfo.MethodInfo>());
														methodsToTrack.get(m.owner).add(m);
														if(PRINT_DEBUG)
															System.out.println("Need to track: " + m);
													}
												}
											}
									} else if (!newCI.equals(old))
										newCI.hasStructuralProblems = true;
								}
							}
							for(ClassInfo ci : ef.newClasses.values())
								ret.put(ci.className.replace('.', '/'),ci);
						}
					}
					else
					{
						//Not a diff we can track.
						ret.put(diff.getNewPath(), null);
						if(LOG_VERBOSE_MYSQL)
						{
							try{
								insertDiff.setInt(1, version_id);
								insertDiff.setInt(2, getCovNameId(diff.getNewPath()));
								insertDiff.setInt(3, 0);
								insertDiff.setInt(4, 0);
								insertDiff.setInt(5, 1);
								insertDiff.setInt(6, 0);
								insertDiff.setInt(7, 0);
								insertDiff.setInt(8, 0);
								insertDiff.setInt(9, 0);
								insertDiff.setInt(10, 0);
								insertDiff.setInt(11, 0);

								insertDiff.addBatch();
							}
							catch(SQLException ex)
							{
								ex.printStackTrace();
							}
						}
					}
				}
				f.close();
			}
			for(String c : methodsToTrack.keySet())
			{
				if(ret.containsKey(c))
				{
					ret.get(c).newMethods.addAll(methodsToTrack.get(c));
				}
				else
				{
					ClassInfo cl = new ClassInfo();
					cl.className = c;
					cl.edits = new LinkedList<Edit>();
					cl.newMethods = new ArrayList<ClassInfo.MethodInfo>(methodsToTrack.get(c));
					ret.put(c, cl);
				}
			}
			HashSet<String> toRemove =  new HashSet<String>();
			for(String s : ret.keySet())
			{
				ClassInfo ci  =ret.get(s);
				if (ci == null || (ci.edits == null && ci.edits.size() == 0 && !ci.hasEditedAnnotation && !ci.hasStructuralProblems))
					toRemove.add(s);
			}
			for(String s : toRemove)
				ret.remove(s);
				
			return ret;
		} finally {
			if (revWalk != null)
				revWalk.close();
		}
	}
	private static boolean equals(String s1, String s2) {
		if(s1 ==null && s2 == null)
			return true;
		else if(s1 == null || s2 == null)
			return false;
		return s1.equals(s2);
	}

}
