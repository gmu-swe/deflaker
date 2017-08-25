package org.deflaker.diff;

import java.io.Serializable;
import java.util.HashMap;

public class EditedFile implements Serializable {

	private static final long serialVersionUID = 4528883250213163032L;
	String fileName;


	@Override
	public String toString() {
		return "EditedFile [hasStructuralProblems=" + hasStructuralProblem() + ", fileName=" + fileName + ", oldClass=\n\t" + oldClasses + ",\nnewClass=\n\t" + newClasses + "]";
	}
	public HashMap<String, ClassInfo> oldClasses = new HashMap<String, ClassInfo>();
	public HashMap<String, ClassInfo> newClasses = new HashMap<String, ClassInfo>();
	public boolean hasStructuralProblem()
	{
		if(!oldClasses.equals(newClasses)) return true;
		for(ClassInfo ci : oldClasses.values())
		{
			if(ci.hasEditedAnnotation())
				return true;
		}
		for(ClassInfo ci : newClasses.values())
		{
			if(ci.hasEditedAnnotation())
				return true;
		}
		return false;
	}
}