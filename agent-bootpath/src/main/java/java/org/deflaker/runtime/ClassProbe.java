package java.org.deflaker.runtime;


public class ClassProbe {
	public boolean hit;
	protected final Class<?> c;

	public ClassProbe(Class<?> c) {
		this.c = c;
	}

	public void hit() {
		if(!hit)
		{
			DiffCovAgent.registerClassHit(c);
		}
		hit = true;
	}
}
