package org.jenkinsci.plugins.ghprb;

import com.google.common.annotations.VisibleForTesting;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHPullRequestCommitDetail.Commit;
import org.kohsuke.github.GHUser;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.ConcurrentMap;

public class GhprbPullRequestMerge extends Recorder {

    private PrintStream logger;

    private String mergeComment;

    @DataBoundConstructor
    public GhprbPullRequestMerge(String mergeComment) {
        this.mergeComment = mergeComment;
    }

    public String getMergeComment() {
        return mergeComment;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    private Ghprb helper;

    @VisibleForTesting
    void setHelper(Ghprb helper) {
        this.helper = helper;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener) throws InterruptedException, IOException {
        logger = listener.getLogger();
        AbstractProject<?, ?> project = build.getProject();
        if (build.getResult().isWorseThan(Result.SUCCESS)) {
            logger.println("Build did not succeed, merge will not be run");
            return true;
        }

        GhprbTrigger trigger = Ghprb.extractTrigger(project);
        if (trigger == null)
            return false;

        GhprbCause cause = getCause(build);
        if (cause == null) {
            return true;
        }

        ConcurrentMap<Integer, GhprbPullRequest> pulls = trigger.getDescriptor().getPullRequests(project.getFullName());

        GHPullRequest pr = pulls.get(cause.getPullID()).getPullRequest();

        if (pr == null) {
            logger.println("Pull request is null for ID: " + cause.getPullID());
            logger.println("" + pulls.toString());
            return false;
        }

        Boolean isMergeable = cause.isMerged();

        if (helper == null) {
            helper = new Ghprb(project, trigger, pulls);
            helper.init();
        }

        if (isMergeable == null || !isMergeable) {
            logger.println("Pull request cannot be automerged.");
            listener.finished(Result.FAILURE);
            return false;
        }

        logger.println("Merging the pull request");

        pr.merge(getMergeComment());
        logger.println("Pull request successfully merged");

        listener.finished(Result.SUCCESS);
        return true;
    }

    private boolean isOwnCode(GHPullRequest pr, GHUser committer) {
        try {
            String commentorName = committer.getName();
            for (GHPullRequestCommitDetail detail : pr.listCommits()) {
                Commit commit = detail.getCommit();
                String committerName = commit.getCommitter().getName();

                if (committerName.equalsIgnoreCase(commentorName)) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.println("Unable to get committer name");
            e.printStackTrace(logger);
        }
        return false;
    }

    private GhprbCause getCause(AbstractBuild<?, ?> build) {
        Cause cause = build.getCause(GhprbCause.class);
        if (cause == null)
            return null;
        return (GhprbCause) cause;
    }

    @Extension(ordinal = -1)
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        @Override
        public String getDisplayName() {
            return "Github Pull Request Merger";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public FormValidation doCheck(@AncestorInPath AbstractProject<?, ?> project, @QueryParameter String value) throws IOException {
            return FilePath.validateFileMask(project.getSomeWorkspace(), value);
        }

    }

}
