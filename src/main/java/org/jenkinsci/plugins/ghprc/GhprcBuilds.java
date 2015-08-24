package org.jenkinsci.plugins.ghprc;

import com.google.common.base.Strings;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.TaskListener;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.git.util.BuildData;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.ghprc.extensions.GhprcCommitStatus;
import org.jenkinsci.plugins.ghprc.extensions.GhprcCommitStatusException;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtension;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author janinko
 */
public class GhprcBuilds {
    private static final Logger logger = Logger.getLogger(GhprcBuilds.class.getName());
    private final GhprcTrigger trigger;
    private final GhprcRepository repo;

    public GhprcBuilds(GhprcTrigger trigger, GhprcRepository repo) {
        this.trigger = trigger;
        this.repo = repo;
    }

    public void build(GhprcPullRequest pr) {

        GhprcCause cause = new GhprcCause(pr.getHead(), pr.getId(), pr.isMergeable(), pr.getTarget(), pr.getSource(), pr.getAuthorEmail(),
                pr.getTitle(), pr.getUrl(), pr.getCommitAuthor());

        for (GhprcExtension ext : Ghprc.getJobExtensions(trigger, GhprcCommitStatus.class)) {
            if (ext instanceof GhprcCommitStatus) {
                try {
                    ((GhprcCommitStatus) ext).onBuildTriggered(trigger, pr, repo.getGitHubRepo());
                } catch (GhprcCommitStatusException e) {
                    repo.commentOnFailure(null, null, e);
                }
            }
        }
        QueueTaskFuture<?> build = trigger.startJob(cause, repo);
        if (build == null) {
            logger.log(Level.SEVERE, "Job did not start");
        }
    }


    public void onStarted(AbstractBuild<?, ?> build, TaskListener listener) {
        PrintStream logger = listener.getLogger();
        GhprcCause c = Ghprc.getCause(build);
        if (c == null) {
            return;
        }

        GhprcTrigger trigger = Ghprc.extractTrigger(build);

        ConcurrentMap<Integer, GhprcPullRequest> pulls = trigger.getDescriptor().getPullRequests(build.getProject().getFullName());

        GHPullRequest pr = pulls.get(c.getPullID()).getPullRequest();

        try {
            int counter = 0;
            // If the PR is being resolved by GitHub then getMergeable will return null
            Boolean isMergeable = pr.getMergeable();
            Boolean isMerged = pr.isMerged();
            // Not sure if isMerged can return null, but adding if just in case
            if (isMerged == null) {
                isMerged = false;
            }
            while (isMergeable == null && !isMerged && counter++ < 60) {
                Thread.sleep(1000);
                isMergeable = pr.getMergeable();
                isMerged = pr.isMerged();
                if (isMerged == null) {
                    isMerged = false;
                }
            }
            
            if (isMerged) {
                logger.println("PR has already been merged, builds using the merged sha1 will fail!!!");
            } else if (isMergeable == null) {
                logger.println("PR merge status couldn't be retrieved, maybe GitHub hasn't settled yet");
            } else if (isMergeable != c.isMerged()) {
                logger.println("!!! PR mergeability status has changed !!!  ");
                 if (isMergeable) {
                    logger.println("PR now has NO merge conflicts");
                } else if (!isMergeable) {
                    logger.println("PR now has merge conflicts!");
                }
            }
            
        } catch (Exception e) {
            logger.print("Unable to query GitHub for status of PullRequest");
            e.printStackTrace(logger);
        }

        for (GhprcExtension ext : Ghprc.getJobExtensions(trigger, GhprcCommitStatus.class)) {
            if (ext instanceof GhprcCommitStatus) {
                try {
                    ((GhprcCommitStatus) ext).onBuildStart(build, listener, repo.getGitHubRepo());
                } catch (GhprcCommitStatusException e) {
                    repo.commentOnFailure(build, listener, e);
                }
            }
        }
        
        try {
            String template = trigger.getBuildDescTemplate();
            if (StringUtils.isEmpty(template)) {
                template = "<a title=\"$title\" href=\"$url\">PR #$pullId</a>: $abbrTitle";
            }
            Map<String, String> vars = getVariables(c);
            template = Util.replaceMacro(template, vars);
            template = Ghprc.replaceMacros(build, listener, template);
            build.setDescription(template);
        } catch (IOException ex) {
            logger.print("Can't update build description");
            ex.printStackTrace(logger);
        }
    }

    public Map<String, String> getVariables(GhprcCause c) {
      Map<String, String> vars = new HashMap<String, String>();
      vars.put("title", c.getTitle());
      vars.put("url", c.getUrl().toString());
      vars.put("pullId", Integer.toString(c.getPullID()));
      vars.put("abbrTitle", c.getAbbreviatedTitle());
      return vars;
    }

    public void onCompleted(AbstractBuild<?, ?> build, TaskListener listener) {
        GhprcCause c = Ghprc.getCause(build);
        if (c == null) {
            return;
        }

        // remove the BuildData action that we may have added earlier to avoid
        // having two of them, and because the one we added isn't correct
        // @see GhprcTrigger
        BuildData fakeOne = null;
        for (BuildData data : build.getActions(BuildData.class)) {
            if (data.getLastBuiltRevision() != null && !data.getLastBuiltRevision().getSha1String().equals(c.getCommit())) {
                fakeOne = data;
                break;
            }
        }
        if (fakeOne != null) {
            build.getActions().remove(fakeOne);
        }

        for (GhprcExtension ext : Ghprc.getJobExtensions(trigger, GhprcCommitStatus.class)) {
            if (ext instanceof GhprcCommitStatus) {
                try {
                    ((GhprcCommitStatus) ext).onBuildComplete(build, listener, repo.getGitHubRepo());
                } catch (GhprcCommitStatusException e) {
                    repo.commentOnFailure(build, listener, e);
                }
            }
        }

        GHCommitState state;
        state = Ghprc.getState(build);

        commentOnBuildResult(build, listener, c);
        // close failed pull request automatically
        if (state == GHCommitState.FAILURE && trigger.isAutoCloseFailedPullRequests()) {
            closeFailedRequest(listener, c);
        }
    }

    private void closeFailedRequest(TaskListener listener, GhprcCause c) {
        try {
            GHPullRequest pr = repo.getPullRequest(c.getPullID());

            if (pr.getState().equals(GHIssueState.OPEN)) {
                repo.closePullRequest(c.getPullID());
            }
        } catch (IOException ex) {
            listener.getLogger().println("Can't close pull request");
            ex.printStackTrace(listener.getLogger());
        }
    }

    private void commentOnBuildResult(AbstractBuild<?, ?> build, TaskListener listener, GhprcCause c) {
        String outputFilename = getWorkspace(build) + getOutputFilename(build, listener);
        File outputFile = new File(outputFilename);

        String msg;
        try {
            msg = FileUtils.readFileToString(outputFile);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable to read comment from output file at " + outputFilename);
            return;
        }

        String md5 = DigestUtils.md5Hex(msg);

        if (!Strings.isNullOrEmpty(msg)) {
            repo.addOrUpdateComment(c.getPullID(), msg, md5, build, listener);
        }
    }

    private String getOutputFilename(AbstractBuild<?, ?> build, TaskListener listener) {
        return Ghprc.replaceMacros(build, listener, trigger.getDescriptor().getOutputFile());
    }

    private String getWorkspace(AbstractBuild<?, ?> build) {
        return build.getWorkspace().getRemote() + File.separator;
    }

}
