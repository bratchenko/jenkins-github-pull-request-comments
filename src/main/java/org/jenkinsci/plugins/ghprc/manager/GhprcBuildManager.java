package org.jenkinsci.plugins.ghprc.manager;

import java.util.Iterator;

/**
 * @author mdelapenya (Manuel de la Peña)
 */
public interface GhprcBuildManager {

    /**
     * Calculate the build URL of a build
     * 
     * @return the build URL
     */
    String calculateBuildUrl(String publishedURL);

    /**
     * Returns downstream builds as an iterator
     * 
     * @return the iterator
     */
    Iterator<?> downstreamProjects();

    /**
     * Print tests result of a build in one line.
     *
     * @return the tests result
     */
    String getOneLineTestResults();

    /**
     * Print tests result of a build
     *
     * @return the tests result
     */
    String getTestResults();

}