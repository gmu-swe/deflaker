package java.org.deflaker.runtime;

import java.lang.reflect.Field;
import java.util.ArrayList;

public class ReflectionMasker {
	public static Field[] removeCovFields(Field[] in) {
		ArrayList<Field> ret = new ArrayList<Field>(in.length);
		boolean changed = false;
		for(Field f : in)
		{
			if(!f.getName().startsWith("$$deflaker"))
				ret.add(f);
			else
				changed = true;
		}
		if(changed)
		{
			Field[] r = new Field[ret.size()];
			r = ret.toArray(r);
			return r;
		}
		return in;
	}

	public static Class[] removeCovInterfaces(Class[] in) {
		ArrayList<Class> ret = new ArrayList<Class>(in.length);
		for(Class c : in)
			if(c != TrackedClassLevelClass.class && c != TrackedClass.class)
				ret.add(c);
		Class[] r = new Class[ret.size()];
		r = ret.toArray(r);
		return r;
	}
}
