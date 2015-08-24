package org.jenkinsci.plugins.ghprc.extensions;

import hudson.model.AbstractBuild;
import hudson.model.TaskListener;

import org.jenkinsci.plugins.ghprc.GhprcPullRequest;
import org.jenkinsci.plugins.ghprc.GhprcTrigger;
import org.kohsuke.github.GHRepository;

public interface GhprcCommitStatus {
    
    public void onBuildTriggered(GhprcTrigger trigger, GhprcPullRequest pr, GHRepository ghRepository) throws GhprcCommitStatusException;
    public void onBuildStart(AbstractBuild<?, ?> build, TaskListener listener, GHRepository repo) throws GhprcCommitStatusException;
    public void onBuildComplete(AbstractBuild<?, ?> build, TaskListener listener, GHRepository repo) throws GhprcCommitStatusException;

}
