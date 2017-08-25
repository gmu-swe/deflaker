package org.deflaker.debug;
import java.io.File;
import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.Upload;
import com.sun.management.HotSpotDiagnosticMXBean;

/*
 * Adapted from https://blogs.oracle.com/sundararajan/programmatically-dumping-heap-from-java-applications
 */
public class TestFailureCatcher {
	private static final String AWS_BUCKET = System.getenv("DIFFCOV_AWS_BUCKET");
	public static String testName;
	public static final boolean HEAP_DUMP = System.getenv("DIFFCOV_HEAP_DUMP") != null || (System.getenv("TRAVIS") != null && System.getenv("DIFFCOV_AWS_ACCESS_KEY") != null && System.getenv("DIFFCOV_AWS_ACCESS_KEY") != null);

	private static String sanitizeTest(String test)
	{
		if(test == null)
			return null;
		return test.replace('[', '-').replace(']', '-').replace('?', '-').replace('#', '-').replace('/', '-').replace('&', '-').replace('!', '-');
	}
	public static void catchTestFailure() {
		if (HEAP_DUMP) {
			testName = sanitizeTest(testName);
			String fileName = testName + "_" + System.currentTimeMillis() + ".hprof";
			System.out.println(testName + " about to fail! Generating heap dump " + fileName);
			dumpHeap(fileName);
			if (System.getenv("TRAVIS") != null) {
				String keyName = System.getenv("TRAVIS_REPO_SLUG") + "/" + System.getenv("TRAVIS_JOB_ID") + "/"
						+ fileName;
				AmazonS3 s3client = AmazonS3ClientBuilder.standard().withRegion(Regions.US_EAST_1)
						.withCredentials(new AWSCredentialsProvider() {

							@Override
							public void refresh() {

							}

							@Override
							public AWSCredentials getCredentials() {
								return new BasicAWSCredentials(System.getenv("DIFFCOV_AWS_ACCESS_KEY"),
										System.getenv("DIFFCOV_AWS_SECRET_KEY"));
							}
						}).build();
				TransferManager tm = TransferManagerBuilder.standard().withS3Client(s3client).build();
				PutObjectRequest upRq = new PutObjectRequest(AWS_BUCKET, keyName, new File(fileName));
				upRq.setCannedAcl(CannedAccessControlList.PublicRead);
				Upload upload = tm.upload(upRq);

				try {
					upload.waitForCompletion();
					System.out.println("Uploaded heap dump: https://s3.amazonaws.com/flakyheapdumps/" + keyName);
				} catch (AmazonServiceException e) {
					e.printStackTrace();
				} catch (AmazonClientException e) {
					e.printStackTrace();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				tm.shutdownNow();
			}
		}
	}


    private static final String HOTSPOT_BEAN_NAME =
            "com.sun.management:type=HotSpotDiagnostic";
       // field to store the hotspot diagnostic MBean 
       private static volatile HotSpotDiagnosticMXBean hotspotMBean;

       static void dumpHeap(String fileName) {
           // initialize hotspot diagnostic MBean
    	   File toDump = new File(fileName);
    	   if(toDump.exists())
    		   toDump.delete();
           initHotspotMBean();
           try {
               hotspotMBean.dumpHeap(fileName, true);
           } catch (RuntimeException re) {
               throw re;
           } catch (Exception exp) {
               throw new RuntimeException(exp);
           }
       }
       // initialize the hotspot diagnostic MBean field
       private static void initHotspotMBean() {
           if (hotspotMBean == null) {
               synchronized (TestFailureCatcher.class) {
                   if (hotspotMBean == null) {
                       hotspotMBean = getHotspotMBean();
                   }
               }
           }
       }
       // get the hotspot diagnostic MBean from the
       // platform MBean server
       private static HotSpotDiagnosticMXBean getHotspotMBean() {
           try {
               MBeanServer server = ManagementFactory.getPlatformMBeanServer();
               HotSpotDiagnosticMXBean bean = 
                   ManagementFactory.newPlatformMXBeanProxy(server,
                   HOTSPOT_BEAN_NAME, HotSpotDiagnosticMXBean.class);
               return bean;
           } catch (RuntimeException re) {
               throw re;
           } catch (Exception exp) {
               throw new RuntimeException(exp);
           }
       }	
}
