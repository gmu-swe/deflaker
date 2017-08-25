package org.deflaker.diff;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Paths;
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
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
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

public class LineAnalyzer {
	public static void main(String[] args) throws CorruptObjectException, MissingObjectException, IOException {
		HashMap<String, ClassInfo> edits = getChanges("/Users/jon/Documents/GMU/Projects/surefire-diff-coverage/experiments/okhttp/.git", "d173c9e908a8ce1e9e02e79d16e12aa2905d724f");
		for (ClassInfo e : edits.values()) {
//			System.out.println(e);
			System.out.println(e.className + (e.hasStructuralProblems)+": " + e.edits);
			// if(e.getChangeType() == ChangeType.MODIFY)
//			annotateSource(e);
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
	public static void collectStructuralElements(final EditedFile e, byte[] src, final LinkedList<Edit> edits, final boolean isNew) throws FileNotFoundException, UnsupportedEncodingException {

		ASTParser p = ASTParser.newParser(AST.JLS8);
		p.setBindingsRecovery(false);
		p.setResolveBindings(false);
		p.setUnitName("App.java");
		// String[] encodings = new String[srcPath.length];
		// for (int i = 0; i < srcPath.length; i++)
		// encodings[i] = "UTF-8";
//		System.out.println(e.fileName);
		p.setEnvironment(new String[0], new String[0], new String[0], true);
		Map<String, String> options = JavaCore.getOptions();
		JavaCore.setComplianceOptions(JavaCore.VERSION_1_8, options);
		p.setCompilerOptions(options);
		p.setKind(ASTParser.K_COMPILATION_UNIT);

		p.setSource(new String(src,"UTF-8").toCharArray()); //TODO fix char encodings
		final CompilationUnit root = (CompilationUnit) p.createAST(null);
		for (IProblem prob : root.getProblems()) {
			System.out.println(prob);
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
				if(thisClass.className == null)
					thisClass.className= name.replace('.', '/');
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
					thisClass.filterEditsFromParents();
					if (isNew)
						e.newClasses.put(name.replace('.', '/'), thisClass);
					else
						e.oldClasses.put(name.replace('.', '/'), thisClass);
				}
				if(node.getSuperclassType() != null)
					thisClass.superName = node.getSuperclassType().toString();
				thisClass.className = name;
				return super.visit(node);
			}

			private boolean visitAnnotation(Annotation node)
			{
				if(isEdited(edits, root, node))
					thisClass.hasEditedAnnotation = true;
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
			String toDesc(String binaryName){
				if(binaryName.length() == 1)
					return binaryName;
				else if(binaryName.charAt(0) == '[')
					return binaryName.replace('.', '/');// +";";
				else
					return "L" + binaryName.replace('.', '/') +";";
			}
			@Override
			public boolean visit(MethodDeclaration node) {
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
					if(b != null)
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
					if(b != null)
						fq.append(toDesc(b.getBinaryName()));
					else
						fq.append(toDesc(node.getReturnType2().toString()));
				}
				thisClass.methods.add(new MethodInfo(fq.toString(), name, thisClass.className));
//				System.out.println(fq.toString());
//				System.out.println(node);
				return true;
			}

		});
	}

	public static HashMap<String, ClassInfo> getChanges(String gitDir, String _commit) throws CorruptObjectException, MissingObjectException, IOException {
		RevWalk revWalk = null;
		HashMap<String, ClassInfo> ret = new HashMap<String, ClassInfo>();
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
						collectStructuralElements(ef, loader.getBytes(), edits, true);
						for(ClassInfo ci : ef.newClasses.values())
						{
							ci.hasStructuralProblems = true;
							ret.put(ci.className.replace('.', '/'),ci);
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
							collectStructuralElements(ef, loader.getBytes(), edits, true);
							
							edits = new LinkedList<Edit>();
							for (org.eclipse.jgit.diff.Edit e : el) {
								edits.add(new Edit(e.getBeginA()+1, e.getEndA()));
							}
							AbbreviatedObjectId oldId = diff.getOldId();
							loader = repo.open(oldId.toObjectId());
							collectStructuralElements(ef, loader.getBytes(), edits, false);
							for(String name : ef.newClasses.keySet())
							{
								ClassInfo newCI = ef.newClasses.get(name);
								ClassInfo old = ef.oldClasses.get(name);
								if(old == null)
									newCI.hasStructuralProblems = true;
								else if(newCI.hasEditedAnnotation)
									newCI.hasStructuralProblems = true;
								else if(!newCI.equals(old))
									newCI.hasStructuralProblems = true;
							}
							for(ClassInfo ci : ef.newClasses.values())
								ret.put(ci.className.replace('.', '/'),ci);
						}
					}
					else
					{
						//Not a diff we can track.
						ret.put(diff.getNewPath(), null);
					}
				}
				f.close();
			}
			return ret;
		} finally {
			if (revWalk != null)
				revWalk.close();
		}
	}

}
