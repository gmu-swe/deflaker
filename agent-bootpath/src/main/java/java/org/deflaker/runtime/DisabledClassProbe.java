package java.org.deflaker.runtime;


public class DisabledClassProbe extends ClassProbe {

	public DisabledClassProbe() {
		super(null);
	}
	public DisabledClassProbe(String src) {
		super(null);
		if(src.startsWith("org/apache/log"))
			System.out.println("Class probe load: " + src);
	}
	@Override
	public void hit() {
	}

}
