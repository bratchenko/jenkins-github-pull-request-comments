package org.jenkinsci.plugins.ghprc;

import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitUser;

import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maintains state about a Pull Request for a particular Jenkins job. This is what understands the current state of a PR for a particular job.
 *
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public class GhprcPullRequest {

    private static final Logger logger = Logger.getLogger(GhprcPullRequest.class.getName());

    private final int id;
    private final GHUser author;
    private final GHPullRequest pr;
    private String title;
    private Date updated;
    private String head;
    private boolean mergeable;
    private String reponame;
    private String target;
    private String source;
    private String authorEmail;
    private URL url;

    private GitUser commitAuthor;

    private transient Ghprc helper;
    private transient GhprcRepository repo;


    GhprcPullRequest(GHPullRequest pr, Ghprc helper, GhprcRepository repo) {
        id = pr.getNumber();
        try {
            updated = pr.getUpdatedAt();
        } catch (IOException e) {
            e.printStackTrace();
            updated = new Date();
        }
        head = pr.getHead().getSha();
        title = pr.getTitle();
        author = pr.getUser();
        reponame = repo.getName();
        target = pr.getBase().getRef();
        source = pr.getHead().getRef();
        url = pr.getHtmlUrl();
        this.pr = pr;
        obtainAuthorEmail(pr);

        this.helper = helper;
        this.repo = repo;

        logger.log(Level.INFO, "Created Pull Request #{0} on {1} by {2} ({3}) updated at: {4} SHA: {5}",
                new Object[] { id, reponame, author.getLogin(), authorEmail, updated, head }
        );
    }

    public void init(Ghprc helper, GhprcRepository repo) {
        this.helper = helper;
        this.repo = repo;
        if (reponame == null) {
            reponame = repo.getName(); // If this instance was created before v1.8, it can be null.
        }
    }

    /**
     * Checks this Pull Request representation against a GitHub version of the Pull Request, and triggers a build if necessary.
     *
     * @param pr pr
     */
    public void check(GHPullRequest pr) {
        if (helper.isProjectDisabled()) {
            logger.log(Level.INFO, "Project is disabled, ignoring pull request");
            return;
        }
        if (target == null) {
            target = pr.getBase().getRef(); // If this instance was created before target was introduced (before v1.8), it can be null.
        }
        if (source == null) {
            source = pr.getHead().getRef(); // If this instance was created before target was introduced (before v1.8), it can be null.
        }

        updatePR(pr, author);
        
        tryBuild(pr);
    }

    private void updatePR(GHPullRequest pr, GHUser author) {
        if (pr != null && isUpdated(pr)) {
            logger.log(Level.INFO, "Pull request #{0} was updated on {1} at {2} by {3}", new Object[] { id, reponame, updated, author });

            // the title could have been updated since the original PR was opened
            title = pr.getTitle();
            boolean newCommit = checkCommit(pr.getHead().getSha());

            if (!newCommit) {
                logger.log(Level.INFO, "Pull request #{0} was updated on repo {1} but there aren''t any new comments nor commits; "
                        + "that may mean that commit status was updated.", 
                        new Object[] { id, reponame }
                );
            }
        }
    }

    private boolean isUpdated(GHPullRequest pr) {
        if (pr == null) {
            return false;
        }
        Date lastUpdated;
        boolean ret = false;
        try {
            lastUpdated = pr.getUpdatedAt();
            ret = updated.compareTo(lastUpdated) < 0;
            updated = lastUpdated;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Unable to update last updated date", e);
        }
        return ret || !pr.getHead().getSha().equals(head);
    }

    private void tryBuild(GHPullRequest pr) {
        if (helper.isProjectDisabled()) {
            logger.log(Level.INFO, "Project is disabled, not trying to build");
            return;
        }
        logger.log(Level.INFO, "Running the build");

        if (authorEmail == null) {
            // If this instance was create before authorEmail was introduced (before v1.10), it can be null.
            obtainAuthorEmail(pr);
            logger.log(Level.INFO, "Author email was not set, trying to set it to {0}", authorEmail);
        }

        if (pr != null) {
            logger.log(Level.INFO, "PR is not null, checking if mergable");
            checkMergeable(pr);
            try {
                for (GHPullRequestCommitDetail commitDetails : pr.listCommits()) {
                    if (commitDetails.getSha().equals(getHead())) {
                        commitAuthor = commitDetails.getCommit().getCommitter();
                        break;
                    }
                }
            } catch (Exception ex) {
                logger.log(Level.INFO, "Unable to get PR commits: ", ex);
            }

        }

        logger.log(Level.INFO, "Running build...");
        build();

    }

    private void build() {
        helper.getBuilds().build(this);
    }

    // returns false if no new commit
    private boolean checkCommit(String sha) {
        if (head.equals(sha)) {
            return false;
        }
        logger.log(Level.INFO, "New commit. Sha: {0} => {1}", new Object[] { head, sha });
        head = sha;
        return true;
    }

    private void checkMergeable(GHPullRequest pr) {
        try {
            int r = 5;
            Boolean isMergeable = pr.getMergeable();
            while (isMergeable == null && r-- > 0) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    break;
                }
                isMergeable = pr.getMergeable();
                pr = repo.getPullRequest(id);
            }
            mergeable = isMergeable != null && isMergeable;
        } catch (IOException e) {
            mergeable = false;
            logger.log(Level.SEVERE, "Couldn't obtain mergeable status.", e);
        }
    }

    private void obtainAuthorEmail(GHPullRequest pr) {
        try {
            authorEmail = pr.getUser().getEmail();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Couldn't obtain author email.", e);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof GhprcPullRequest)) {
            return false;
        }
        GhprcPullRequest o = (GhprcPullRequest) obj;
        return o.id == id;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 89 * hash + this.id;
        return hash;
    }

    public int getId() {
        return id;
    }

    public String getHead() {
        return head;
    }

    public boolean isMergeable() {
        return mergeable;
    }

    public String getTarget() {
        return target;
    }

    public String getSource() {
        return source;
    }

    public String getAuthorEmail() {
        return authorEmail;
    }

    public String getTitle() {
        return title;
    }

    /**
     * Returns the URL to the Github Pull Request.
     *
     * @return the Github Pull Request URL
     */
    public URL getUrl() {
        return url;
    }

    public GitUser getCommitAuthor() {
        return commitAuthor;
    }

    public GHPullRequest getPullRequest() {
        return pr;
    }
}
