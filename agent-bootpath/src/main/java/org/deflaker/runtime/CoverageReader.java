package org.deflaker.runtime;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashSet;
import java.util.Scanner;

public class CoverageReader {
    public static HashSet<String> getTestsCoveringChanges(File covFile) {
        try {
            Scanner covScanner = new Scanner(covFile);
            HashSet<String> ret = new HashSet<>();
            while (covScanner.hasNextLine()) {
                try {
                    TestLineHit h = TestLineHit.fromString(covScanner.nextLine());
                    ret.add(h.testClass + "#" + h.testMethod);
                } catch (ArrayIndexOutOfBoundsException ex) {
                    // File might be corrupt?
                }
            }
            covScanner.close();
            return ret;
        } catch (FileNotFoundException ex) {
            throw new IllegalStateException("Coverage file " + covFile + " can't be found", ex);
        }
    }
}
