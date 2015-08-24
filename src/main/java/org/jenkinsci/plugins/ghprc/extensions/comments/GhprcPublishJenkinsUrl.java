package org.jenkinsci.plugins.ghprc.extensions.comments;

import hudson.Extension;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;

import org.jenkinsci.plugins.ghprc.Ghprc;
import org.jenkinsci.plugins.ghprc.GhprcTrigger;
import org.jenkinsci.plugins.ghprc.extensions.GhprcCommentAppender;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtension;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtensionDescriptor;
import org.jenkinsci.plugins.ghprc.extensions.GhprcGlobalExtension;
import org.jenkinsci.plugins.ghprc.manager.GhprcBuildManager;
import org.jenkinsci.plugins.ghprc.manager.configuration.JobConfiguration;
import org.jenkinsci.plugins.ghprc.manager.factory.GhprcBuildManagerFactoryUtil;
import org.kohsuke.stapler.DataBoundConstructor;

public class GhprcPublishJenkinsUrl extends GhprcExtension implements GhprcCommentAppender, GhprcGlobalExtension {

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    
    private final String publishedURL;
    
    @DataBoundConstructor
    public GhprcPublishJenkinsUrl(String publishedURL) {
        this.publishedURL = publishedURL;
    }
    
    public String getPublishedURL() {
        return publishedURL;
    }

    public String postBuildComment(AbstractBuild<?, ?> build, TaskListener listener) {
        StringBuilder msg = new StringBuilder();

        msg.append("\nRefer to this link for build results (access rights to CI server needed): \n");
        msg.append(generateCustomizedMessage(build));
        msg.append("\n");
        
        return msg.toString();
    }
    

    private String generateCustomizedMessage(AbstractBuild<?, ?> build) {
        GhprcTrigger trigger = Ghprc.extractTrigger(build);
        if (trigger == null) {
            return "";
        }
        JobConfiguration jobConfiguration = JobConfiguration.builder()
                .printStackTrace(trigger.isDisplayBuildErrorsOnDownstreamBuilds()).build();

        GhprcBuildManager buildManager = GhprcBuildManagerFactoryUtil.getBuildManager(build, jobConfiguration);

        StringBuilder sb = new StringBuilder();

        sb.append(buildManager.calculateBuildUrl(publishedURL));

        if (build.getResult() != Result.SUCCESS) {
            sb.append(buildManager.getTestResults());
        }

        return sb.toString();
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    public static final class DescriptorImpl extends GhprcExtensionDescriptor implements GhprcGlobalExtension {

        @Override
        public String getDisplayName() {
            return "Add link to Jenkins";
        }
        
    }
}
