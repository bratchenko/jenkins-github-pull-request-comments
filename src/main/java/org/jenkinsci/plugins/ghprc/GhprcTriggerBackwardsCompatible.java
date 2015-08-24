package org.jenkinsci.plugins.ghprc;

import antlr.ANTLRException;
import hudson.model.AbstractProject;
import hudson.triggers.Trigger;
import hudson.util.DescribableList;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtension;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtensionDescriptor;
import org.jenkinsci.plugins.ghprc.extensions.comments.GhprcCommentFile;
import org.jenkinsci.plugins.ghprc.extensions.status.GhprcSimpleStatus;


public abstract class GhprcTriggerBackwardsCompatible extends Trigger<AbstractProject<?, ?>> {
    
    public abstract DescribableList<GhprcExtension, GhprcExtensionDescriptor> getExtensions();
    

    protected Integer configVersion;

    public GhprcTriggerBackwardsCompatible(String cron) throws ANTLRException {
        super(cron);
    }
    

    @Deprecated
    protected transient String commentFilePath;
    @Deprecated
    protected transient String commitStatusContext;
    @Deprecated
    protected transient GhprcGitHubAuth gitHubApiAuth;
    
    
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
            GhprcCommentFile comments = new GhprcCommentFile(commentFilePath);
            addIfMissing(comments);
            commentFilePath = null;
        }
    }
    
    private void checkCommitStatusContext() {
        if (configVersion < 1) {
            GhprcSimpleStatus status = new GhprcSimpleStatus(commitStatusContext);
            addIfMissing(status);
        }
    }
    
    protected void addIfMissing(GhprcExtension ext) {
        if (getExtensions().get(ext.getClass()) == null) {
            getExtensions().add(ext);
        }
    }

    
}
