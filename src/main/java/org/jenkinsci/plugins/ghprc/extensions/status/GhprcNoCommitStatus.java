package org.jenkinsci.plugins.ghprc.extensions.status;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;

import org.jenkinsci.plugins.ghprc.GhprcPullRequest;
import org.jenkinsci.plugins.ghprc.GhprcTrigger;
import org.jenkinsci.plugins.ghprc.extensions.GhprcCommitStatus;
import org.jenkinsci.plugins.ghprc.extensions.GhprcCommitStatusException;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtension;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtensionDescriptor;
import org.jenkinsci.plugins.ghprc.extensions.GhprcProjectExtension;
import org.kohsuke.github.GHRepository;
import org.kohsuke.stapler.DataBoundConstructor;

public class GhprcNoCommitStatus extends GhprcExtension implements GhprcCommitStatus, GhprcProjectExtension {
    

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    @DataBoundConstructor
    public GhprcNoCommitStatus() {
        
    }
    
    public void onBuildTriggered(GhprcTrigger trigger, GhprcPullRequest pr, GHRepository ghRepository) throws GhprcCommitStatusException {
        
    }

    public void onBuildStart(AbstractBuild<?, ?> build, TaskListener listener, GHRepository repo) throws GhprcCommitStatusException {
        
    }

    public void onBuildComplete(AbstractBuild<?, ?> build, TaskListener listener, GHRepository repo) throws GhprcCommitStatusException {
        
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }
    
    public static final class DescriptorImpl extends GhprcExtensionDescriptor implements GhprcProjectExtension {

        @Override
        public String getDisplayName() {
            return "Do not update commit status";
        }
        
    }

}
