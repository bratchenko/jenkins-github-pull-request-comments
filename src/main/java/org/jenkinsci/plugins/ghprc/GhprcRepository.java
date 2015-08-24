package org.jenkinsci.plugins.ghprc;

import com.google.common.annotations.VisibleForTesting;
import hudson.model.AbstractBuild;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.ghprc.extensions.GhprcCommitStatusException;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHEventPayload.PullRequest;
import org.kohsuke.github.GHHook;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public class GhprcRepository {

    private static final Logger logger = Logger.getLogger(GhprcRepository.class.getName());
    private static final EnumSet<GHEvent> HOOK_EVENTS = EnumSet.of(GHEvent.ISSUE_COMMENT, GHEvent.PULL_REQUEST);
    private static final String MD5_SIGNATURE_PREFIX = "\nmd5 hash: ";
    private static final String MD5_SIGNATURE_REGEXP = MD5_SIGNATURE_PREFIX + "([a-f0-9]{32})";
    private static final Pattern MD5_SIGNATURE_PATTERN = Pattern.compile(MD5_SIGNATURE_REGEXP);

    private final String reponame;
    private final ConcurrentMap<Integer, GhprcPullRequest> pulls;

    private GHRepository ghRepository;
    private Ghprc helper;

    public GhprcRepository(String user, String repository, Ghprc helper, ConcurrentMap<Integer, GhprcPullRequest> pulls) {
        this.reponame = user + "/" + repository;
        this.helper = helper;
        this.pulls = pulls;
    }

    public void init() {
        for (GhprcPullRequest pull : pulls.values()) {
            pull.init(helper, this);
        }
        // make the initial check call to populate our data structures
        initGhRepository();
    }

    private boolean initGhRepository() {
        GitHub gitHub = null;
        try {
            GhprcGitHub repo = helper.getGitHub();
            if (repo == null) {
                return false;
            }
            gitHub = repo.get();
            if (gitHub == null) {
                logger.log(Level.SEVERE, "No connection returned to GitHub server!");
                return false;
            }
            if (gitHub.getRateLimit().remaining == 0) {
                return false;
            }
        } catch (FileNotFoundException ex) {
            logger.log(Level.INFO, "Rate limit API not found.");
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Error while accessing rate limit API", ex);
            return false;
        }

        if (ghRepository == null) {
            try {
                ghRepository = gitHub.getRepository(reponame);
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Could not retrieve GitHub repository named " + reponame + " (Do you have properly set 'GitHub project' field in job configuration?)", ex);
                return false;
            }
        }
        return true;
    }

    public void check() {
        if (!initGhRepository()) {
            return;
        }

        if (helper.isProjectDisabled()) {
            logger.log(Level.FINE, "Project is disabled, not checking github state");
            return;
        }

        List<GHPullRequest> openPulls;
        try {
            openPulls = ghRepository.getPullRequests(GHIssueState.OPEN);
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Could not retrieve open pull requests.", ex);
            return;
        }
        Set<Integer> closedPulls = new HashSet<Integer>(pulls.keySet());

        for (GHPullRequest pr : openPulls) {
            if (pr.getHead() == null) {
                try {
                    pr = ghRepository.getPullRequest(pr.getNumber());
                } catch (IOException ex) {
                    logger.log(Level.SEVERE, "Could not retrieve pr " + pr.getNumber(), ex);
                    return;
                }
            }
            check(pr);
            closedPulls.remove(pr.getNumber());
        }

        // remove closed pulls so we don't check them again
        for (Integer id : closedPulls) {
            pulls.remove(id);
        }
    }

    private void check(GHPullRequest pr) {
        final Integer id = pr.getNumber();
        GhprcPullRequest pull;
        if (pulls.containsKey(id)) {
            pull = pulls.get(id);
        } else {
            pulls.putIfAbsent(id, new GhprcPullRequest(pr, helper, this));
            pull = pulls.get(id);
        }
        pull.check(pr);
    }

    public void commentOnFailure(AbstractBuild<?, ?> build, TaskListener listener, GhprcCommitStatusException ex) {
        PrintStream stream = null;
        if (listener != null) {
            stream = listener.getLogger();
        }
        GHCommitState state = ex.getState();
        Exception baseException = ex.getException();
        String newMessage;
        if (baseException instanceof FileNotFoundException) {
            newMessage = "FileNotFoundException means that the credentials Jenkins is using is probably wrong. Or the user account does not have write access to the repo.";
        } else {
            newMessage = "Could not update commit status of the Pull Request on GitHub.";
        }
        if (stream != null) {
            stream.println(newMessage);
            baseException.printStackTrace(stream);
        } else {
            logger.log(Level.INFO, newMessage, baseException);
        }
    }

    public String getName() {
        return reponame;
    }

    public void addOrUpdateComment(int id, String comment, String md5, AbstractBuild<?, ?> build, TaskListener listener) {
        if (comment.trim().isEmpty())
            return;

        if (build != null && listener != null) {
            try {
                comment = build.getEnvironment(listener).expand(comment);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error", e);
            }
        }

        try {
            addOrUpdateComment(getGitHubRepo().getPullRequest(id), comment, md5);
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Couldn't add comment to pull request #" + id + ": '" + comment + "'", ex);
        }
    }

    private void addOrUpdateComment(GHPullRequest pullRequest, String commentStr, String md5) throws IOException {
        List<GHIssueComment> commentsList;
        try {
            commentsList = pullRequest.getComments();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error loading comments from pull request", e);
            return;
        }
        String myself;
        try {
            myself = helper.getGitHub().get().getMyself().getLogin();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error getting myself from github", e);
            return;
        }
        GHIssueComment myComment = null;
        for (GHIssueComment comment : commentsList) {
            try {
                if (myself.equals(comment.getUser().getLogin())) {
                    myComment = comment;
                    break;
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Error getting user from comment", e);
                return;
            }
        }
        commentStr = commentStr + MD5_SIGNATURE_PREFIX + md5;
        if (myComment == null) {
            pullRequest.comment(commentStr);
            logger.log(Level.FINE, "Posted new comment");
        } else {

            Matcher matcher = MD5_SIGNATURE_PATTERN.matcher(myComment.getBody());

            String prevMd5 = null;
            while (matcher.find()) {
                prevMd5 = matcher.group(1);
            }

            boolean changed = prevMd5 == null || !md5.equals(prevMd5);
            if (changed) {
                myComment.update(commentStr);
                logger.log(Level.FINE, "Updated comment body");
            } else {
                logger.log(Level.FINE, "Comment body has not changed");
            }
        }
    }

    public void closePullRequest(int id) {
        try {
            getGitHubRepo().getPullRequest(id).close();
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Couldn't close the pull request #" + id + ": '", ex);
        }
    }

    private boolean hookExist() throws IOException {
        GHRepository ghRepository = getGitHubRepo();
        for (GHHook h : ghRepository.getHooks()) {
            if (!"web".equals(h.getName())) {
                continue;
            }
            if (!getHookUrl().equals(h.getConfig().get("url"))) {
                continue;
            }
            return true;
        }
        return false;
    }

    public boolean createHook() {
        if (ghRepository == null) {
            logger.log(Level.INFO, "Repository not available, cannot set pull request hook for repository {0}", reponame);
            return false;
        }
        try {
            if (hookExist()) {
                return true;
            }
            Map<String, String> config = new HashMap<String, String>();
            config.put("url", new URL(getHookUrl()).toExternalForm());
            config.put("insecure_ssl", "1");
            ghRepository.createHook("web", config, HOOK_EVENTS, true);
            return true;
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Couldn''t create web hook for repository {0}. Does the user (from global configuration) have admin rights to the repository?", reponame);
            return false;
        }
    }

    private static String getHookUrl() {
        return Jenkins.getInstance().getRootUrl() + GhprcRootAction.URL + "/";
    }

    public GHPullRequest getPullRequest(int id) throws IOException {
        return getGitHubRepo().getPullRequest(id);
    }

    void onPullRequestHook(PullRequest pr) {
        if ("closed".equals(pr.getAction())) {
            pulls.remove(pr.getNumber());
        } else if (helper.isProjectDisabled()) {
            logger.log(Level.FINE, "Not processing Pull request since the build is disabled");
        } else if ("opened".equals(pr.getAction()) || "reopened".equals(pr.getAction())) {
            GhprcPullRequest pull = pulls.get(pr.getNumber());
            if (pull == null) {
                pulls.putIfAbsent(pr.getNumber(), new GhprcPullRequest(pr.getPullRequest(), helper, this));
                pull = pulls.get(pr.getNumber());
            }
            pull.check(pr.getPullRequest());
        } else if ("synchronize".equals(pr.getAction())) {
            GhprcPullRequest pull = pulls.get(pr.getNumber());
            if (pull == null) {
                pulls.putIfAbsent(pr.getNumber(), new GhprcPullRequest(pr.getPullRequest(), helper, this));
                pull = pulls.get(pr.getNumber());
            }
            if (pull == null) {
                logger.log(Level.SEVERE, "Pull Request #{0} doesn''t exist", pr.getNumber());
                return;
            }
            pull.check(pr.getPullRequest());
        } else {
            logger.log(Level.WARNING, "Unknown Pull Request hook action: {0}", pr.getAction());
        }
        GhprcTrigger.getDscp().save();
    }

    @VisibleForTesting
    void setHelper(Ghprc helper) {
        this.helper = helper;
    }

    public GHRepository getGitHubRepo() {
        if (ghRepository == null) {
            init();
        }
        return ghRepository;
    }
}