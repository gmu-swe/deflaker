package org.deflaker.diff;

import java.io.Serializable;


public class FieldInfo implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 3034965142412295912L;
	public String name;
	public String desc;
	public String init;
	@Override
	public String toString() {
		return "Field [" + name + ":" + desc +", init=" + init + "]";
	}
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
		if (getClass() != obj.getClass())
			return false;
		FieldInfo other = (FieldInfo) obj;
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
}