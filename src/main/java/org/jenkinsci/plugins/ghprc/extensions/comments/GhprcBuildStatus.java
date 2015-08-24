package org.jenkinsci.plugins.ghprc.extensions.comments;

import java.util.ArrayList;
import java.util.List;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;

import org.jenkinsci.plugins.ghprc.GhprcTrigger;
import org.jenkinsci.plugins.ghprc.extensions.GhprcCommentAppender;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtension;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtensionDescriptor;
import org.jenkinsci.plugins.ghprc.extensions.GhprcGlobalExtension;
import org.jenkinsci.plugins.ghprc.extensions.GhprcProjectExtension;
import org.kohsuke.stapler.DataBoundConstructor;

public class GhprcBuildStatus extends GhprcExtension implements GhprcCommentAppender, GhprcGlobalExtension, GhprcProjectExtension {
    
    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    private final List<GhprcBuildResultMessage> messages;

    @DataBoundConstructor
    public GhprcBuildStatus(List<GhprcBuildResultMessage> messages) {
        this.messages = messages;
    }
    
    public List<GhprcBuildResultMessage> getMessages() {
        return messages == null ? new ArrayList<GhprcBuildResultMessage>(0) : messages;
    }
    

    public String postBuildComment(AbstractBuild<?, ?> build, TaskListener listener) {
        StringBuilder msg = new StringBuilder();
        
        for (GhprcBuildResultMessage messager: messages) {
            msg.append(messager.postBuildComment(build, listener));
        }
        
        return msg.toString();
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    public static class DescriptorImpl extends GhprcExtensionDescriptor implements GhprcGlobalExtension, GhprcProjectExtension {
        
        @Override
        public String getDisplayName() {
            return "Build Status Messages";
        }
        

        public List<GhprcBuildResultMessage> getMessageList(List<GhprcBuildResultMessage> messages) {
            List<GhprcBuildResultMessage> newMessages = new ArrayList<GhprcBuildResultMessage>(10);
            if (messages != null){
                newMessages.addAll(messages);
            } else {
                for(GhprcExtension extension : GhprcTrigger.getDscp().getExtensions()) {
                    if (extension instanceof GhprcBuildStatus) {
                        newMessages.addAll(((GhprcBuildStatus)extension).getMessages());
                    }
                }
            }
            return newMessages;
        }
        
    }
}
