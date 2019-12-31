package org.deflaker.runtime;

import java.io.Serializable;

public class TestLineHit implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 6951300369782502722L;
	public String testClass;
	public String testMethod;
	public String probeClass;
	public boolean isClassLevel;
	public boolean isBackupClassLevel;

	public int line;
	public String methName;

	public String write() {
		StringBuilder sb = new StringBuilder();
		sb.append(Base64.toBase64(testClass));
		sb.append('\t');
		sb.append(Base64.toBase64(testMethod));
		sb.append('\t');
		sb.append(Base64.toBase64(probeClass));
		sb.append('\t');
		sb.append(isClassLevel);
		sb.append('\t');
		sb.append(isBackupClassLevel);
		sb.append('\t');
		sb.append(line);
		if(methName != null)
		{
			sb.append('\t');
			sb.append(Base64.toBase64(methName));
		}
		sb.append('\n');
		return sb.toString();
	}

	public TestLineHit(String testClass, String testMethod, String probeClass, boolean isClassLevel, int line) {
		super();
		this.testClass = testClass;
		this.probeClass = probeClass;
		this.testMethod = testMethod;
		this.isClassLevel = isClassLevel;
		this.line = line;
	}
	public TestLineHit(String testClass, String testMethod, String probeClass, boolean isClassLevel, boolean isBackupClassLevel, int line) {
		super();
		this.testClass = testClass;
		this.probeClass = probeClass;
		this.testMethod = testMethod;
		this.isClassLevel = isClassLevel;
		this.isBackupClassLevel = isBackupClassLevel;
		this.line = line;
	}
	
	public TestLineHit(String testClass, String testMethod, String probeClass, boolean isClassLevel, String methName) {
		super();
		this.testClass = testClass;
		this.probeClass = probeClass;
		this.testMethod = testMethod;
		this.isClassLevel = isClassLevel;
		this.methName = methName;
	}


	public static TestLineHit fromString(String serialized) {
		String[] d = serialized.split("\t");
		if(d.length == 7)
			return new TestLineHit(Base64.fromBase64(d[0]), Base64.fromBase64(d[1]),  Base64.fromBase64(d[2]), Boolean.valueOf(d[3]), Base64.fromBase64(d[6]));
		else
			return new TestLineHit(Base64.fromBase64(d[0]), Base64.fromBase64(d[1]),  Base64.fromBase64(d[2]), Boolean.valueOf(d[3]), Boolean.valueOf(d[4]), Integer.valueOf(d[5]));
	}

	@Override
	public String toString() {
		return "TestLineHit [testClass=" + testClass + ", testMethod=" + testMethod +", probeClass=" + probeClass + ", isClassLevel=" + isClassLevel + ", line=" + line + "]";
	}
}
