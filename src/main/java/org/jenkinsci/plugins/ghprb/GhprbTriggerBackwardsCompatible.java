package org.jenkinsci.plugins.ghprb;

import antlr.ANTLRException;
import hudson.model.AbstractProject;
import hudson.triggers.Trigger;
import hudson.util.DescribableList;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.ghprb.extensions.GhprbExtension;
import org.jenkinsci.plugins.ghprb.extensions.GhprbExtensionDescriptor;
import org.jenkinsci.plugins.ghprb.extensions.comments.GhprbCommentFile;
import org.jenkinsci.plugins.ghprb.extensions.status.GhprbSimpleStatus;


public abstract class GhprbTriggerBackwardsCompatible extends Trigger<AbstractProject<?, ?>> {
    
    public abstract DescribableList<GhprbExtension, GhprbExtensionDescriptor> getExtensions();
    

    protected Integer configVersion;

    public GhprbTriggerBackwardsCompatible(String cron) throws ANTLRException {
        super(cron);
    }
    

    @Deprecated
    protected transient String commentFilePath;
    @Deprecated
    protected transient String commitStatusContext;
    @Deprecated
    protected transient GhprbGitHubAuth gitHubApiAuth;
    
    
    protected void convertPropertiesToExtensions() {
        if (configVersion == null) {
            configVersion = 0;
        }
        
        checkCommentsFile();
        checkCommitStatusContext();
        
        configVersion = 2;
    }
    
    private void checkCommentsFile() {
        if (!StringUtils.isEmpty(commentFilePath)) {
            GhprbCommentFile comments = new GhprbCommentFile(commentFilePath);
            addIfMissing(comments);
            commentFilePath = null;
        }
    }
    
    private void checkCommitStatusContext() {
        if (configVersion < 1) {
            GhprbSimpleStatus status = new GhprbSimpleStatus(commitStatusContext);
            addIfMissing(status);
        }
    }
    
    protected void addIfMissing(GhprbExtension ext) {
        if (getExtensions().get(ext.getClass()) == null) {
            getExtensions().add(ext);
        }
    }

    
}
