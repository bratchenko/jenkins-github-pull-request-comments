package org.jenkinsci.plugins.ghprc.extensions.status;

import hudson.Extension;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;

import org.jenkinsci.plugins.ghprc.Ghprc;
import org.jenkinsci.plugins.ghprc.GhprcCause;
import org.jenkinsci.plugins.ghprc.GhprcPullRequest;
import org.jenkinsci.plugins.ghprc.GhprcTrigger;
import org.jenkinsci.plugins.ghprc.extensions.GhprcCommitStatus;
import org.jenkinsci.plugins.ghprc.extensions.GhprcCommitStatusException;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtension;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtensionDescriptor;
import org.jenkinsci.plugins.ghprc.extensions.GhprcGlobalExtension;
import org.jenkinsci.plugins.ghprc.extensions.GhprcProjectExtension;
import org.jenkinsci.plugins.ghprc.extensions.comments.GhprcBuildResultMessage;
import org.jenkinsci.plugins.ghprc.manager.GhprcBuildManager;
import org.jenkinsci.plugins.ghprc.manager.configuration.JobConfiguration;
import org.jenkinsci.plugins.ghprc.manager.factory.GhprcBuildManagerFactoryUtil;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.stapler.DataBoundConstructor;

public class GhprcSimpleStatus extends GhprcExtension implements GhprcCommitStatus, GhprcGlobalExtension, GhprcProjectExtension {

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    
    private final String commitStatusContext;
    private final String triggeredStatus;
    private final String startedStatus;
    private final String statusUrl;
    private final List<GhprcBuildResultMessage> completedStatus;
    
    public GhprcSimpleStatus() {
        this(null, null, null, null, new ArrayList<GhprcBuildResultMessage>(0));
    }
    
    public GhprcSimpleStatus(String commitStatusContext) {
        this(commitStatusContext, null, null, null, new ArrayList<GhprcBuildResultMessage>(0));
    }
    
    @DataBoundConstructor
    public GhprcSimpleStatus(
            String commitStatusContext,
            String statusUrl,
            String triggeredStatus,
            String startedStatus,
            List<GhprcBuildResultMessage> completedStatus
    ) {
        this.statusUrl = statusUrl;
        this.commitStatusContext = commitStatusContext == null ? "" : commitStatusContext;
        this.triggeredStatus = triggeredStatus;
        this.startedStatus = startedStatus;
        this.completedStatus = completedStatus;
    }
    
    public String getStatusUrl() {
        return statusUrl == null ? "" : statusUrl;
    }
    
    public String getCommitStatusContext() {
        return commitStatusContext == null ? "" : commitStatusContext;
    }

    public String getStartedStatus() {
        return startedStatus == null ? "" : startedStatus;
    }
    
    public String getTriggeredStatus() {
        return triggeredStatus == null ? "" : triggeredStatus;
    }

    public List<GhprcBuildResultMessage> getCompletedStatus() {
        return completedStatus == null ? new ArrayList<GhprcBuildResultMessage>(0) : completedStatus;
    }
    
    public void onBuildTriggered(GhprcTrigger trigger, GhprcPullRequest pr, GHRepository ghRepository) throws GhprcCommitStatusException {
        StringBuilder sb = new StringBuilder();
        GHCommitState state = GHCommitState.PENDING;
        
        AbstractProject<?, ?> project = trigger.getActualProject();
        
        String context = Util.fixEmpty(commitStatusContext);
        context = Ghprc.replaceMacros(project, context);
        
        if (!StringUtils.isEmpty(triggeredStatus)) {
            sb.append(Ghprc.replaceMacros(project, triggeredStatus));
        } else {
            sb.append("Build triggered.");
            if (pr.isMergeable()) {
                sb.append(" sha1 is merged.");
            } else {
                sb.append(" sha1 is original commit.");
            }
        }
        
        String url = Ghprc.replaceMacros(project, statusUrl);

        String message = sb.toString();
        try {
            ghRepository.createCommitStatus(pr.getHead(), state, url, message, context);
        } catch (IOException e) {
            throw new GhprcCommitStatusException(e, state, message, pr.getId());
        }
    }

    public void onBuildStart(AbstractBuild<?, ?> build, TaskListener listener, GHRepository repo) throws GhprcCommitStatusException {
        GhprcCause c = Ghprc.getCause(build);
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isEmpty(startedStatus)) {
            sb.append("Build started");
            sb.append(c.isMerged() ? " sha1 is merged." : " sha1 is original commit.");
        } else {
            sb.append(Ghprc.replaceMacros(build, listener, startedStatus));
        }
        createCommitStatus(build, listener, sb.toString(), repo, GHCommitState.PENDING);
    }

    public void onBuildComplete(AbstractBuild<?, ?> build, TaskListener listener, GHRepository repo) throws GhprcCommitStatusException {
        
        GHCommitState state = Ghprc.getState(build);

        StringBuilder sb = new StringBuilder();

        if (completedStatus == null || completedStatus.isEmpty()) {
            sb.append("Build finished.");
        } else {
            for (GhprcBuildResultMessage buildStatus : completedStatus) {
                sb.append(buildStatus.postBuildComment(build, listener));
            }
        }
        
        sb.append(" ");
        GhprcTrigger trigger = Ghprc.extractTrigger(build);
        if (trigger == null) {
            listener.getLogger().println("Unable to get pull request builder trigger!!");
        } else {
            JobConfiguration jobConfiguration =
                JobConfiguration.builder()
                    .printStackTrace(trigger.isDisplayBuildErrorsOnDownstreamBuilds())
                    .build();

            GhprcBuildManager buildManager =
                GhprcBuildManagerFactoryUtil.getBuildManager(build, jobConfiguration);
            sb.append(buildManager.getOneLineTestResults());
        }
        
        createCommitStatus(build, listener, sb.toString(), repo, state);
    }

    private void createCommitStatus(AbstractBuild<?, ?> build, TaskListener listener, String message, GHRepository repo, GHCommitState state) throws GhprcCommitStatusException {
        GhprcCause cause = Ghprc.getCause(build);
        
        String sha1 = cause.getCommit();
        String url = Jenkins.getInstance().getRootUrl() + build.getUrl();
        if (!StringUtils.isEmpty(statusUrl)) {
            url = Ghprc.replaceMacros(build, listener, statusUrl);
        }
        String context = Util.fixEmpty(commitStatusContext);
        context = Ghprc.replaceMacros(build, listener, context);
        
        listener.getLogger().println(String.format("Setting status of %s to %s with url %s and message: '%s'", sha1, state, url, message));
        if (context != null) {
            listener.getLogger().println(String.format("Using context: " + context));
        }
        try {
            repo.createCommitStatus(sha1, state, url, message, context);
        } catch (IOException e) {
            throw new GhprcCommitStatusException(e, state, message, cause.getPullID());
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }
    
    public static final class DescriptorImpl extends GhprcExtensionDescriptor implements GhprcGlobalExtension, GhprcProjectExtension {
        
        @Override
        public String getDisplayName() {
            return "Update commit status during build";
        }
        
        public String getTriggeredStatusDefault(String triggeredStatusLocal) {
            String triggeredStatus = triggeredStatusLocal;
            if (triggeredStatus == null) {
                for(GhprcExtension extension : GhprcTrigger.getDscp().getExtensions()) {
                    if (extension instanceof GhprcSimpleStatus) {
                        triggeredStatus = ((GhprcSimpleStatus) extension).getTriggeredStatus();
                        break;
                    }
                }
            }
            return triggeredStatus;
        }
        
        public String getStartedStatusDefault(String startedStatusLocal) {
            String startedStatus = startedStatusLocal;
            if (startedStatus == null) {
                for(GhprcExtension extension : GhprcTrigger.getDscp().getExtensions()) {
                    if (extension instanceof GhprcSimpleStatus) {
                        startedStatus = ((GhprcSimpleStatus) extension).getStartedStatus();
                        break;
                    }
                }
            }
            return startedStatus;
        }
        
        public List<GhprcBuildResultMessage> getCompletedStatusList(List<GhprcBuildResultMessage> completedStatusLocal) {
            List<GhprcBuildResultMessage> completedStatus = completedStatusLocal;
            if (completedStatus == null) {
                for(GhprcExtension extension : GhprcTrigger.getDscp().getExtensions()) {
                    if (extension instanceof GhprcSimpleStatus) {
                        completedStatus = ((GhprcSimpleStatus) extension).getCompletedStatus();
                        break;
                    }
                }
            }
            return completedStatus;
        }
        
        public String getCommitContextDefault(String commitStatusContextLocal){
            String commitStatusContext = commitStatusContextLocal;
            if (commitStatusContext == null) {
                for(GhprcExtension extension : GhprcTrigger.getDscp().getExtensions()) {
                    if (extension instanceof GhprcSimpleStatus) {
                        commitStatusContext = ((GhprcSimpleStatus) extension).getCommitStatusContext();
                        break;
                    }
                }
            }
            return commitStatusContext;
        }
    }


}
