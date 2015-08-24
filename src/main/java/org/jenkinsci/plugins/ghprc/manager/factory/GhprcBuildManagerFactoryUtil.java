package org.jenkinsci.plugins.ghprc.manager.factory;

import com.cloudbees.plugins.flow.FlowRun;

import hudson.model.AbstractBuild;
import org.jenkinsci.plugins.ghprc.manager.configuration.JobConfiguration;
import org.jenkinsci.plugins.ghprc.manager.GhprcBuildManager;
import org.jenkinsci.plugins.ghprc.manager.impl.GhprcDefaultBuildManager;
import org.jenkinsci.plugins.ghprc.manager.impl.downstreambuilds.BuildFlowBuildManager;

/**
 * @author mdelapenya (Manuel de la Peña)
 */
public class GhprcBuildManagerFactoryUtil {

    /**
     * Gets an instance of a library that is able to calculate build urls depending of build type.
     * 
     * If the class representing the build type is not present on the classloader then default implementation is returned.
     * 
     * @param build
     * @return
     */
    public static GhprcBuildManager getBuildManager(AbstractBuild<?, ?> build) {
        JobConfiguration jobConfiguration = JobConfiguration.builder().printStackTrace(false).build();

        return getBuildManager(build, jobConfiguration);
    }

    public static GhprcBuildManager getBuildManager(AbstractBuild<?, ?> build, JobConfiguration jobConfiguration) {
        try {
            if (build instanceof FlowRun) {
                return new BuildFlowBuildManager(build, jobConfiguration);
            }
        } catch (NoClassDefFoundError ncdfe) {}

        return new GhprcDefaultBuildManager(build);
    }

}