package java.org.deflaker.runtime;


public class BackupClassProbe extends ClassProbe {
	public BackupClassProbe(Class<?> c) {
		super(c);
	}

	public void hit() {
		if(!hit)
		{
			DiffCovAgent.registerBackupClassHit(c);
		}
		hit = true;
	}
}
