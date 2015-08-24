package org.jenkinsci.plugins.ghprc.manager.impl;

import hudson.model.AbstractBuild;

/**
 * @author mdelapenya (Manuel de la Peña)
 */
public class GhprcDefaultBuildManager extends GhprcBaseBuildManager {

    public GhprcDefaultBuildManager(AbstractBuild<?, ?> build) {
        super(build);
    }

}