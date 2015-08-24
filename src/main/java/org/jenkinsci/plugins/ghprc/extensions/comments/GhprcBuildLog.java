package org.jenkinsci.plugins.ghprc.extensions.comments;

import java.io.IOException;
import java.util.List;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;

import org.jenkinsci.plugins.ghprc.Ghprc;
import org.jenkinsci.plugins.ghprc.extensions.GhprcCommentAppender;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtension;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtensionDescriptor;
import org.jenkinsci.plugins.ghprc.extensions.GhprcGlobalExtension;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.stapler.DataBoundConstructor;

public class GhprcBuildLog extends GhprcExtension implements GhprcCommentAppender, GhprcGlobalExtension {

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    
    private final Integer logExcerptLines;

    @DataBoundConstructor
    public GhprcBuildLog(Integer logExcerptLines) {
        this.logExcerptLines = logExcerptLines;
    }
    
    public Integer getLogExcerptLines() {
        return logExcerptLines == null ? 0 : logExcerptLines;
    }

    public String postBuildComment(AbstractBuild<?, ?> build, TaskListener listener) {
        
        StringBuilder msg = new StringBuilder();
        GHCommitState state = Ghprc.getState(build);

        int numLines = getLogExcerptLines();
        
        if (state != GHCommitState.SUCCESS && numLines > 0) {
            // on failure, append an excerpt of the build log
            try {
                // wrap log in "code" markdown
                msg.append("\n\n**Build Log**\n*last ").append(numLines).append(" lines*\n");
                msg.append("\n ```\n");
                List<String> log = build.getLog(numLines);
                for (String line : log) {
                    msg.append(line).append('\n');
                }
                msg.append("```\n");
            } catch (IOException ex) {
                listener.getLogger().println("Can't add log excerpt to commit comments");
                ex.printStackTrace(listener.getLogger());
            }
        }
        return msg.toString();
    }

    public boolean ignorePublishedUrl() {
        return false;
    }
    

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }
    

    public static final class DescriptorImpl extends GhprcExtensionDescriptor implements GhprcGlobalExtension {

        @Override
        public String getDisplayName() {
            return "Append portion of build log";
        }
    }

}
