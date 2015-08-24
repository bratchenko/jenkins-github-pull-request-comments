package org.jenkinsci.plugins.ghprc.extensions;

import hudson.model.AbstractBuild;
import hudson.model.TaskListener;

public interface GhprcCommentAppender {

    public String postBuildComment(AbstractBuild<?, ?> build, TaskListener listener);
    
}
