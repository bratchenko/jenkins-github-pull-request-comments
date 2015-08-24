package org.jenkinsci.plugins.ghprc.extensions.comments;

import java.io.File;
import java.io.IOException;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;

import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.ghprc.Ghprc;
import org.jenkinsci.plugins.ghprc.extensions.GhprcCommentAppender;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtension;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtensionDescriptor;
import org.jenkinsci.plugins.ghprc.extensions.GhprcProjectExtension;
import org.kohsuke.stapler.DataBoundConstructor;

public class GhprcCommentFile extends GhprcExtension implements GhprcCommentAppender, GhprcProjectExtension {

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    
    private final String commentFilePath;

    @DataBoundConstructor
    public GhprcCommentFile(String commentFilePath) {
        this.commentFilePath = commentFilePath;
    }
    
    public String getCommentFilePath() {
        return commentFilePath != null ? commentFilePath : "";
    }
    
    public boolean ignorePublishedUrl() {
        // TODO Auto-generated method stub
        return false;
    }

    public String postBuildComment(AbstractBuild<?, ?> build, TaskListener listener) {
        StringBuilder msg = new StringBuilder();
        if (commentFilePath != null && !commentFilePath.isEmpty()) {
            try {
                String scriptFilePathResolved = Ghprc.replaceMacros(build, listener, commentFilePath);
                
                String content = FileUtils.readFileToString(new File(scriptFilePathResolved));
                msg.append("Build comment file: \n--------------\n");
                msg.append(content);
                msg.append("\n--------------\n");
            } catch (IOException e) {
                msg.append("\n!!! Couldn't read commit file !!!\n");
                listener.getLogger().println("Couldn't read comment file");
                e.printStackTrace(listener.getLogger());
            }
        }
        return msg.toString();
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }


    public static final class DescriptorImpl extends GhprcExtensionDescriptor implements GhprcProjectExtension {

        @Override
        public String getDisplayName() {
            return "Comment File";
        }
        
    }

}
