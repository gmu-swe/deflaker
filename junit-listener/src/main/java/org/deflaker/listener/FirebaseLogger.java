package org.deflaker.listener;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

import org.deflaker.runtime.MySQLLogger.TestResult;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
//import com.google.firebase.auth.FirebaseCredentials;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.DatabaseReference.CompletionListener;
import com.google.firebase.database.FirebaseDatabase;

public class FirebaseLogger {

	FirebaseDatabase database;
	DatabaseReference buildRef;
	Object initLatch = new Object();
	static AtomicInteger pendingFirebaseRequests = new AtomicInteger();
	public FirebaseLogger() {
		InputStream serviceAccount = new ByteArrayInputStream(System.getenv("FIREBASE_KEY").getBytes());
		FirebaseOptions options = new FirebaseOptions.Builder()
				  .setDatabaseUrl("https://flakytests.firebaseio.com")
				  .setServiceAccount(serviceAccount)
				  .build();

		FirebaseApp.initializeApp(options);
		database = FirebaseDatabase.getInstance();
		
		String repo_name=System.getenv("TRAVIS_REPO_SLUG");
		String build_number = System.getenv("TRAVIS_BUILD_ID");
		String commit = System.getenv("TRAVIS_COMMIT");
		String commit_range = System.getenv("TRAVIS_COMMIT_RANGE");
		String job_id= System.getenv("TRAVIS_JOB_ID");
		buildRef = database.getReference("builds/"+repo_name+"/"+build_number+"/"+job_id);
		pendingFirebaseRequests.incrementAndGet();
		pendingFirebaseRequests.incrementAndGet();
		pendingFirebaseRequests.incrementAndGet();

		buildRef.child("commit").setValue(commit, new CompletionListener() {
			@Override
			public void onComplete(DatabaseError arg0, DatabaseReference arg1) {
				pendingFirebaseRequests.decrementAndGet();
				synchronized (initLatch) {
					initLatch.notify();
				}
			}
		});
		buildRef.child("commit_range").setValue(commit_range, new CompletionListener() {
			@Override
			public void onComplete(DatabaseError arg0, DatabaseReference arg1) {
				pendingFirebaseRequests.decrementAndGet();
				synchronized (initLatch) {
					initLatch.notify();
				}
			}
		});
		buildRef.child("build_date").setValue(System.currentTimeMillis(), new CompletionListener() {
			@Override
			public void onComplete(DatabaseError arg0, DatabaseReference arg1) {
				pendingFirebaseRequests.decrementAndGet();
				synchronized (initLatch) {
					initLatch.notify();
				}
			}
		});
	}

	public void awaitExit()
	{
		while(pendingFirebaseRequests.get() > 0)
		{
			synchronized (initLatch) {
				try {
					initLatch.wait();
				} catch (InterruptedException e) {
				}
			}
		}
	}
	public void log(TestResult tr)
	{
		pendingFirebaseRequests.incrementAndGet();
		HashMap<String, Integer> testInfo =new HashMap<String, Integer>();
		testInfo.put("nFailures", tr.nFailures);
		testInfo.put("nMethods", tr.nMethods);
		buildRef.child("tests/"+sanitizeForFirebase(tr.name)).setValue(testInfo, new DatabaseReference.CompletionListener() {
			
			@Override
			public void onComplete(DatabaseError arg0, DatabaseReference arg1) {
				if(arg0 != null)
					throw arg0.toException();
				pendingFirebaseRequests.decrementAndGet();
				synchronized (initLatch) {
					initLatch.notify();
				}
			}
		});
	}

	String sanitizeForFirebase(String n)
	{
		return n.replace('.', '-').replace('$', '-').replace('#', '-').replace('$', '-').replace('[', '-').replace(']', '-');
	}
	public void logHits(String className, String methodName, LinkedList<String> hitClasses, LinkedList<String> hitBackupClasses, LinkedList<String> hitLines, LinkedList<String> hitMethods) {
		if (hitLines.size() > 0) {
			pendingFirebaseRequests.incrementAndGet();
			buildRef.child("tests/" + sanitizeForFirebase(className) + "-" + sanitizeForFirebase(methodName) + "/coveredlines").setValue(hitLines, new DatabaseReference.CompletionListener() {

				@Override
				public void onComplete(DatabaseError arg0, DatabaseReference arg1) {
					pendingFirebaseRequests.decrementAndGet();
					synchronized (initLatch) {
						initLatch.notify();
					}
					if (arg0 != null)
						throw arg0.toException();
				}
			});
		}
		if (hitClasses.size() > 0) {
			pendingFirebaseRequests.incrementAndGet();

			buildRef.child("tests/" + sanitizeForFirebase(className) + "-" + sanitizeForFirebase(methodName) + "/coveredclasses").setValue(hitClasses, new DatabaseReference.CompletionListener() {

				@Override
				public void onComplete(DatabaseError arg0, DatabaseReference arg1) {
					pendingFirebaseRequests.decrementAndGet();
					synchronized (initLatch) {
						initLatch.notify();
					}
					if (arg0 != null)
						throw arg0.toException();

				}
			});
		}
		if (hitBackupClasses.size() > 0) {
			pendingFirebaseRequests.incrementAndGet();

			buildRef.child("tests/" + sanitizeForFirebase(className) + "-" + sanitizeForFirebase(methodName) + "/coveredextraclasses").setValue(hitBackupClasses, new DatabaseReference.CompletionListener() {

				@Override
				public void onComplete(DatabaseError arg0, DatabaseReference arg1) {
					pendingFirebaseRequests.decrementAndGet();
					synchronized (initLatch) {
						initLatch.notify();
					}
					if (arg0 != null)
						throw arg0.toException();

				}
			});
		}
		if (hitMethods.size() > 0) {
			pendingFirebaseRequests.incrementAndGet();

			buildRef.child("tests/" + sanitizeForFirebase(className) + "-" + sanitizeForFirebase(methodName) + "/coveredmethods").setValue(hitMethods, new DatabaseReference.CompletionListener() {

				@Override
				public void onComplete(DatabaseError arg0, DatabaseReference arg1) {
					pendingFirebaseRequests.decrementAndGet();
					synchronized (initLatch) {
						initLatch.notify();
					}
					if (arg0 != null)
						throw arg0.toException();

				}
			});
		}
	}
}
