package org.deflaker.diff;

import java.io.Serializable;

public class Edit implements Serializable {
	int editStart;
	int editEnd;
	boolean isTryCatch;
	
	public int getEditEnd() {
		return editEnd;
	}
	public int getEditStart() {
		return editStart;
	}
	public Edit(int start, int end) {
		this.editStart = start;
		this.editEnd = end;
	}

	public boolean isInRange(int rangeStart, int rangeEnd) {
		return (editStart <= rangeStart && editEnd >= rangeStart) || (editStart >= rangeStart && editStart <= rangeEnd);
	}

	@Override
	public String toString() {
		return "Edit [start=" + editStart + ", end=" + editEnd + "]";
	}
}