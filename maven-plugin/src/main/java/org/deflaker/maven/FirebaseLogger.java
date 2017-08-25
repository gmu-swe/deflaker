package org.deflaker.maven;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

import org.deflaker.diff.Edit;
import org.deflaker.listener.TestLineHit;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
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


	public void log(String className, boolean hasStructuralProblems, LinkedList<Edit> edits) {
		pendingFirebaseRequests.incrementAndGet();
		className = className.replace('.', '-');
		className = className.replace('/', '-');
		className = className.replace("$", "-INNER-");
		buildRef.child("diff/" + className + "/structural").setValue(hasStructuralProblems ? "true" : "false", new DatabaseReference.CompletionListener() {

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
		if (!edits.isEmpty()) {
			pendingFirebaseRequests.incrementAndGet();
			buildRef.child("diff/" + className + "/edits").setValue(edits, new DatabaseReference.CompletionListener() {

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

	public void logUntestedStructural(String className) {
		pendingFirebaseRequests.incrementAndGet();
		buildRef.child("untestedClass/").push().setValue(className, new DatabaseReference.CompletionListener() {

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

	public void logUntestedLines(String className, ArrayList<Integer> unCovered) {
		pendingFirebaseRequests.incrementAndGet();
		className = className.replace('.', '-');
		className = className.replace('/', '-');
		className = className.replace("$", "-INNER-");
		buildRef.child("untestedLines/"+className).setValue(unCovered, new DatabaseReference.CompletionListener() {

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
	public void logUntestedMethods(String className, ArrayList<String> unCovered) {
		pendingFirebaseRequests.incrementAndGet();
		className = className.replace('.', '-');
		className = className.replace('/', '-');
		className = className.replace("$", "-INNER-");
		buildRef.child("untestedMethods/"+className).setValue(unCovered, new DatabaseReference.CompletionListener() {

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


	public void logFlaky(String className) {
		logFlaky(className, null);
	}

	public void logNotFlakyFailure(String fullClassName, LinkedList<TestLineHit> linkedList) {
		pendingFirebaseRequests.incrementAndGet();
		buildRef.child("failures/notflaky").push().setValue(fullClassName, new DatabaseReference.CompletionListener() {

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

	public void logFlaky(String className, LinkedList<TestLineHit> linkedList) {
		pendingFirebaseRequests.incrementAndGet();
		buildRef.child("failures/flaky").push().setValue(className, new DatabaseReference.CompletionListener() {

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
		if(linkedList != null && linkedList.size() > 0)
		{
			pendingFirebaseRequests.incrementAndGet();
			buildRef.child("failures/maybeflaky").push().setValue(className, new DatabaseReference.CompletionListener() {

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
