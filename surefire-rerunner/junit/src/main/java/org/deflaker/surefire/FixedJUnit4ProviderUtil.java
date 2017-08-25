package org.deflaker.surefire;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.surefire.common.junit4.ClassMethod;
import org.apache.maven.surefire.common.junit4.JUnit4ProviderUtil;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

public class FixedJUnit4ProviderUtil {
	/**
     * Get all test methods from a list of Failures
     *
     * @param allFailures the list of failures for a given test class
     * @return the list of test methods
     */
    public static Set<ClassMethod> generateFailingTests( List<Failure> allFailures )
    {
        Set<ClassMethod> failingMethods = new HashSet<ClassMethod>();

        for ( Failure failure : allFailures )
        {
            Description description = failure.getDescription();
        	if(description.getChildren() != null && description.getChildren().size() == 1)
        		description = description.getChildren().get(0);
            if ( description.isTest() && !JUnit4ProviderUtil.isFailureInsideJUnitItself( description ) )
            {
                ClassMethod classMethod = JUnit4ProviderUtil.cutTestClassAndMethod( description );
                if ( classMethod.isValid() )
                {
                    failingMethods.add( classMethod );
                }
            }
        }
        return failingMethods;
    }
}
