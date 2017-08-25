package org.deflaker.surefire;

import static org.apache.maven.surefire.common.junit4.JUnit4RunListener.isFailureInsideJUnitItself;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.surefire.common.junit4.JUnit4ProviderUtil;
import org.apache.maven.surefire.util.internal.StringUtils;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

public class FixedJUnit4ProviderUtil {
	/**
	 * Get all test methods from a list of Failures
	 *
	 * @param allFailures
	 *            the list of failures for a given test class
	 * @return the list of test methods
	 */
	public static Set<String> generateFailingTests(List<Failure> allFailures) {
		Set<String> failingMethods = new HashSet<String>();

		for (Failure failure : allFailures) {
			if (!isFailureInsideJUnitItself(failure)) {
				// failure.getTestHeader() is in the format: method(class)
				Description description = failure.getDescription();
				if (description.getChildren() != null && description.getChildren().size() == 1)
				{
					description = description.getChildren().get(0);
					String testMethod = StringUtils.split(description.getDisplayName(), "(")[0];
					failingMethods.add(testMethod);
				} else {
					String testMethod = StringUtils.split(failure.getTestHeader(), "(")[0];
					failingMethods.add(testMethod);
				}
			}
		}
		return failingMethods;
	}
	
}
