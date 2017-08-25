package org.deflaker.diff;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;

import org.deflaker.diff.ClassInfo.MethodInfo;



public class ClassInfo implements Serializable{
	public static class MethodInfo implements Serializable {

		/**
		 * 
		 */
		private static final long serialVersionUID = 1555846862915194108L;
		public final String desc;
		public final String name;
		public final String owner;
		
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((desc == null) ? 0 : desc.hashCode());
			result = prime * result + ((name == null) ? 0 : name.hashCode());
			return result;
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if(obj instanceof String)
			{
				return (name+desc).equals(obj);
			}
			if (getClass() != obj.getClass())
				return false;
			MethodInfo other = (MethodInfo) obj;
			if (desc == null) {
				if (other.desc != null)
					return false;
			} else if (!desc.equals(other.desc))
				return false;
			if (name == null) {
				if (other.name != null)
					return false;
			} else if (!name.equals(other.name))
				return false;
			return true;
		}
		public MethodInfo(String desc, String name, String owner) {
			this.desc = desc;
			this.name = name;
			this.owner = owner;
		}

		@Override
		public String toString() {
			return owner+'.'+name+desc;
		}
		
	}
	/**
	 * 
	 */
	private static final long serialVersionUID = 5754856850501744817L;
	public String className;
	public String superName;
	public HashSet<MethodInfo> methods = new HashSet<MethodInfo>();
	public HashSet<FieldInfo> fields = new HashSet<FieldInfo>();
	
	public transient HashSet<FieldInfo> superFields;
	public HashSet<ClassInfo> innerClasses = new HashSet<ClassInfo>();
	public int anonCounter = 1;
	public int lambdaCounter = 1;
	public transient ClassInfo parent;
	public transient HashSet<MethodInfo> superMethods;

	public HashSet<Integer> nonSevereEditedLines = new HashSet<Integer>();
	
	public boolean hasEditedAnnotation;
	
	public boolean containsEditedLine(int line)
	{
		for(Edit e : edits)
			if(e.editStart <= line && e.editEnd >= line)
				return true;
		return false;
	}
	boolean hasEditedAnnotation()
	{
		if(hasEditedAnnotation)
			return true;
		for(ClassInfo ci : innerClasses)
			if(ci.hasEditedAnnotation())
				return true;
		return false;
	}
	public int startLine;
	public int endLine;
	public LinkedList<Edit> edits;
	public HashSet<Integer> codeLinesEdited = new HashSet<Integer>();
	public boolean hasStructuralProblems;
	public ArrayList<MethodInfo> newMethods;
	public transient HashSet<MethodInfo> methodsWithChangedAnnotations = new HashSet<ClassInfo.MethodInfo>();
	@Override
	public String toString() {
		return "ClassInfo [className=" + className + ", superName=" + superName + ", methods=" + methods + ", fields=" + fields + ", innerClasses=" + innerClasses + ", edits = " + edits +"]";
	}
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((className == null) ? 0 : className.hashCode());
//		result = prime * result + ((superName == null) ? 0 : superName.hashCode()); //Can't user supername because it gets changed after added to hashmap!
		return result;
	}
	public boolean equalsWithoutMethodsOrFields(Object obj)
	{
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ClassInfo other = (ClassInfo) obj;
		if (className == null) {
			if (other.className != null)
				return false;
		} else if (!className.equals(other.className))
			return false;
//		if (innerClasses == null) {
//			if (other.innerClasses != null)
//				return false;
//		} else if (!innerClasses.equals(other.innerClasses))
//			return false;
		if (superName == null) {
			if (other.superName != null)
				return false;
		} else if (!superName.equals(other.superName))
			return false;
		return true;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ClassInfo other = (ClassInfo) obj;
		if (className == null) {
			if (other.className != null)
				return false;
		} else if (!className.equals(other.className))
			return false;
		if (fields == null) {
			if (other.fields != null)
				return false;
		} else if (!fields.equals(other.fields))
			return false;
//		if (innerClasses == null) {
//			if (other.innerClasses != null)
//				return false;
//		} else if (!innerClasses.equals(other.innerClasses))
//			return false;
		if (methods == null) {
			if (other.methods != null)
				return false;
		} else if (!methods.equals(other.methods))
			return false;
		if (superName == null) {
			if (other.superName != null)
				return false;
		} else if (!superName.equals(other.superName))
			return false;
		return true;
	}
	public void filterEditsFromParents() {
		if(parent == null)
			return;
		parent.filterEditsFromParents();
		for(Edit e : edits)
		{
			if(parent.edits.contains(e))
				parent.edits.remove(e);
		}
	}
	
}