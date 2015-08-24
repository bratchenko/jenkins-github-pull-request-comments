package org.jenkinsci.plugins.ghprb;

import antlr.ANTLRException;
import com.coravy.hudson.plugins.github.GithubProjectProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Run;
import hudson.model.Saveable;
import hudson.model.StringParameterValue;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.git.util.BuildData;
import hudson.triggers.TriggerDescriptor;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.ghprb.extensions.GhprbCommitStatus;
import org.jenkinsci.plugins.ghprb.extensions.GhprbExtension;
import org.jenkinsci.plugins.ghprb.extensions.GhprbExtensionDescriptor;
import org.jenkinsci.plugins.ghprb.extensions.comments.GhprbBuildLog;
import org.jenkinsci.plugins.ghprb.extensions.comments.GhprbPublishJenkinsUrl;
import org.jenkinsci.plugins.ghprb.extensions.status.GhprbSimpleStatus;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GitHub;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public class GhprbTrigger extends GhprbTriggerBackwardsCompatible {

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    private static final Logger logger = Logger.getLogger(GhprbTrigger.class.getName());
    private final String cron;
    private final String buildDescTemplate;
    private final Boolean useGitHubHooks;
    private Boolean autoCloseFailedPullRequests;
    private Boolean displayBuildErrorsOnDownstreamBuilds;
    private transient Ghprb helper;
    private String project;
    private AbstractProject<?, ?> _project;
    private String gitHubAuthId;
    
    
    private DescribableList<GhprbExtension, GhprbExtensionDescriptor> extensions = new DescribableList<GhprbExtension, GhprbExtensionDescriptor>(Saveable.NOOP);
    
    public DescribableList<GhprbExtension, GhprbExtensionDescriptor> getExtensions() {
        if (extensions == null) {
            extensions = new DescribableList<GhprbExtension, GhprbExtensionDescriptor>(Saveable.NOOP,Util.fixNull(extensions));
            extensions.add(new GhprbSimpleStatus());
        }
        return extensions;
    }
    
    private void setExtensions(List<GhprbExtension> extensions) {
        DescribableList<GhprbExtension, GhprbExtensionDescriptor> rawList = new DescribableList<GhprbExtension, GhprbExtensionDescriptor>(
                Saveable.NOOP,Util.fixNull(extensions));
        
        // Filter out items that we only want one of, like the status updater.
        this.extensions = Ghprb.onlyOneEntry(rawList, 
                                              GhprbCommitStatus.class
                                            );
        
        // Now make sure we have at least one of the types we need one of.
        Ghprb.addIfMissing(this.extensions, new GhprbSimpleStatus(), GhprbCommitStatus.class);
    }

    @DataBoundConstructor
    public GhprbTrigger(String cron,
            Boolean useGitHubHooks,
            Boolean autoCloseFailedPullRequests,
            Boolean displayBuildErrorsOnDownstreamBuilds,
            String gitHubAuthId,
            String buildDescTemplate,
            List<GhprbExtension> extensions) throws ANTLRException {
        super(cron);
        this.cron = cron;
        this.useGitHubHooks = useGitHubHooks;
        this.autoCloseFailedPullRequests = autoCloseFailedPullRequests;
        this.displayBuildErrorsOnDownstreamBuilds = displayBuildErrorsOnDownstreamBuilds;
        this.gitHubAuthId = gitHubAuthId;
        this.buildDescTemplate = buildDescTemplate;
        setExtensions(extensions);
        configVersion = 1;
    }

    @Override
    public Object readResolve() {
        convertPropertiesToExtensions();
        checkGitHubApiAuth();
        return this;
    }

    @SuppressWarnings("deprecation")
    private void checkGitHubApiAuth() {
        if (gitHubApiAuth != null) {
            gitHubAuthId = gitHubApiAuth.getId();
            gitHubApiAuth = null;
        }
    }
    
    public static DescriptorImpl getDscp() {
        return DESCRIPTOR;
    }

    @Override
    public void start(AbstractProject<?, ?> project, boolean newInstance) {
        // We should always start the trigger, and handle cases where we don't run in the run function.
        super.start(project, newInstance);
        this._project = project;
        this.project = project.getFullName();
        
        if (project.isDisabled()) {
            logger.log(Level.FINE, "Project is disabled, not starting trigger for job " + this.project);
            return;
        }
        if (project.getProperty(GithubProjectProperty.class) == null) {
            logger.log(Level.INFO, "GitHub project property is missing the URL, cannot start ghprb trigger for job " + this.project);
            return;
        }
        try {
            helper = createGhprb(project);
        } catch (IllegalStateException ex) {
            logger.log(Level.SEVERE, "Can't start ghprb trigger", ex);
            return;
        }

        logger.log(Level.INFO, "Starting the ghprb trigger for the {0} job; newInstance is {1}", 
                new String[] { this.project, String.valueOf(newInstance) });
        helper.init();
    }

    Ghprb createGhprb(AbstractProject<?, ?> project) {
        return new Ghprb(project, this, getDescriptor().getPullRequests(project.getFullName()));
    }

    @Override
    public void stop() {
        logger.log(Level.INFO, "Stopping the ghprb trigger for project {0}", this.project);
        if (helper != null) {
            helper.stop();
            helper = null;
        }
        super.stop();
    }

    @Override
    public void run() {
        // triggers are always triggered on the cron, but we just no-op if we are using GitHub hooks.
        if (getUseGitHubHooks()) {
            logger.log(Level.FINE, "Use webHooks is set, so not running trigger");
            return;
        }

        if ((helper != null && helper.isProjectDisabled()) || (_project != null && _project.isDisabled())) {
            logger.log(Level.FINE, "Project is disabled, ignoring trigger run call for job {0}", this.project);
            return;
        }
        
        if (helper == null) {
            logger.log(Level.SEVERE, "Helper is null and Project is not disabled, unable to run trigger");
            return;
        }

        
        logger.log(Level.FINE, "Running trigger for {0}", project);
        
        helper.run();
        getDescriptor().save();
    }

    public QueueTaskFuture<?> startJob(GhprbCause cause, GhprbRepository repo) {
        ArrayList<ParameterValue> values = getDefaultParameters();
        final String commitSha = cause.isMerged() ? "origin/pr/" + cause.getPullID() + "/merge" : cause.getCommit();
        values.add(new StringParameterValue("sha1", commitSha));
        values.add(new StringParameterValue("ghprbActualCommit", cause.getCommit()));

        setCommitAuthor(cause, values);

        final StringParameterValue pullIdPv = new StringParameterValue("ghprbPullId", String.valueOf(cause.getPullID()));
        values.add(pullIdPv);
        values.add(new StringParameterValue("ghprbTargetBranch", String.valueOf(cause.getTargetBranch())));
        values.add(new StringParameterValue("ghprbSourceBranch", String.valueOf(cause.getSourceBranch())));
        values.add(new StringParameterValue("GIT_BRANCH", String.valueOf(cause.getSourceBranch())));
        // it's possible the GHUser doesn't have an associated email address
        values.add(new StringParameterValue("ghprbPullAuthorEmail", getString(cause.getAuthorEmail(), "")));
        values.add(new StringParameterValue("ghprbPullDescription", String.valueOf(cause.getShortDescription())));
        values.add(new StringParameterValue("ghprbPullTitle", String.valueOf(cause.getTitle())));
        values.add(new StringParameterValue("ghprbPullLink", String.valueOf(cause.getUrl())));
        values.add(new StringParameterValue("ghprbOutputFile", getDescriptor().getOutputFile()));

        try {
            values.add(new StringParameterValue("ghprbTargetCommit",
                    repo.getGitHubRepo().getBranches().get(cause.getTargetBranch()).getSHA1()));
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable to get branches from github repo", e);
        }

        // add the previous pr BuildData as an action so that the correct change log is generated by the GitSCM plugin
        // note that this will be removed from the Actions list after the job is completed so that the old (and incorrect)
        // one isn't there
        return this.job.scheduleBuild2(job.getQuietPeriod(), cause, new ParametersAction(values), findPreviousBuildForPullId(pullIdPv));
    }
    
    
    public GhprbGitHubAuth getGitHubApiAuth() {
        if (gitHubAuthId == null) {
            for (GhprbGitHubAuth auth: getDescriptor().getGithubAuth()){
                gitHubAuthId = auth.getId();
                getDescriptor().save();
                return auth;
            }
        }
        return getDescriptor().getGitHubAuth(gitHubAuthId);
    }
    

    public GitHub getGitHub() throws IOException {
        GhprbGitHubAuth auth = getGitHubApiAuth();
        if (auth == null) {
            return null;
        }
        
        return auth.getConnection(getActualProject());
    }
    
    public AbstractProject<?, ?> getActualProject() {
        
        if (_project != null) {
            return _project;
        }

        @SuppressWarnings("rawtypes")
        List<AbstractProject> projects = Jenkins.getInstance().getAllItems(AbstractProject.class);
        
        for (AbstractProject<?, ?> project : projects) {
            if (project.getFullName().equals(this.project)) {
                return project;
            }
        }
        return null;
    }

    private void setCommitAuthor(GhprbCause cause, ArrayList<ParameterValue> values) {
        String authorName = "";
        String authorEmail = "";
        if (cause.getCommitAuthor() != null) {
            authorName = getString(cause.getCommitAuthor().getName(), "");
            authorEmail = getString(cause.getCommitAuthor().getEmail(), "");
        }

        values.add(new StringParameterValue("ghprbActualCommitAuthor", authorName));
        values.add(new StringParameterValue("ghprbActualCommitAuthorEmail", authorEmail));
    }

    private String getString(String actual, String d) {
        return actual == null ? d : actual;
    }

    /**
     * Find the previous BuildData for the given pull request number; this may return null
     */
    private BuildData findPreviousBuildForPullId(StringParameterValue pullIdPv) {
        // find the previous build for this particular pull request, it may not be the last build
        for (Run<?, ?> r : job.getBuilds()) {
            ParametersAction pa = r.getAction(ParametersAction.class);
            if (pa != null) {
                for (ParameterValue pv : pa.getParameters()) {
                    if (pv.equals(pullIdPv)) {
                        Iterables.getFirst(r.getActions(BuildData.class), null);
                    }
                }
            }
        }
        return null;
    }

    private ArrayList<ParameterValue> getDefaultParameters() {
        ArrayList<ParameterValue> values = new ArrayList<ParameterValue>();
        ParametersDefinitionProperty pdp = this.job.getProperty(ParametersDefinitionProperty.class);
        if (pdp != null) {
            for (ParameterDefinition pd : pdp.getParameterDefinitions()) {
                if (pd.getName().equals("sha1"))
                    continue;
                values.add(pd.getDefaultParameterValue());
            }
        }
        return values;
    }

    public String getBuildDescTemplate() {
        return buildDescTemplate == null ? "" : buildDescTemplate;
    }

    public String getCron() {
        return cron;
    }

    public String getProject() {
        return project;
    }

    public Boolean getUseGitHubHooks() {
        return useGitHubHooks != null && useGitHubHooks;
    }

    public Boolean isAutoCloseFailedPullRequests() {
        if (autoCloseFailedPullRequests == null) {
            Boolean autoClose = getDescriptor().getAutoCloseFailedPullRequests();
            return (autoClose != null && autoClose);
        }
        return autoCloseFailedPullRequests;
    }

    public Boolean isDisplayBuildErrorsOnDownstreamBuilds() {
        if (displayBuildErrorsOnDownstreamBuilds == null) {
            Boolean displayErrors = getDescriptor().getDisplayBuildErrorsOnDownstreamBuilds();
            return (displayErrors != null && displayErrors);
        }
        return displayBuildErrorsOnDownstreamBuilds;
    }

    public GhprbWebHook getWebHook() {
        GhprbWebHook webHook = new GhprbWebHook(this);
        return webHook;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @VisibleForTesting
    void setHelper(Ghprb helper) {
        this.helper = helper;
    }

    public GhprbBuilds getBuilds() {
        if (helper == null) {
            logger.log(Level.SEVERE, "The ghprb trigger for {0} wasn''t properly started - helper is null", this.project);
            return null;
        }
        return helper.getBuilds();
    }

    public GhprbRepository getRepository() {
        if (helper == null) {
            logger.log(Level.SEVERE, "The ghprb trigger for {0} wasn''t properly started - helper is null", this.project);
            return null;
        }
        return helper.getRepository();
    }

    public static final class DescriptorImpl extends TriggerDescriptor {
        // GitHub username may only contain alphanumeric characters or dashes and cannot begin with a dash
        private Integer configVersion;

        /**
         * These settings only really affect testing. When Jenkins calls configure() then the formdata will 
         * be used to replace all of these fields. Leaving them here is useful for
         * testing, but must not be confused with a default. They also should not be used as the default 
         * value in the global.jelly file as this value is dynamic and will not be
         * retained once configure() is called.
         */
        private String cron = "H/5 * * * *";
        private String outputFile = "output.txt";
        private GHCommitState unstableAs = GHCommitState.FAILURE;
        private Boolean autoCloseFailedPullRequests = false;
        private Boolean displayBuildErrorsOnDownstreamBuilds = false;
        
        private List<GhprbGitHubAuth> githubAuth;
        
        public GhprbGitHubAuth getGitHubAuth(String gitHubAuthId) {
            
            if (gitHubAuthId == null) {
                return getGithubAuth().get(0);
            }
            
            GhprbGitHubAuth firstAuth = null;
            for (GhprbGitHubAuth auth : getGithubAuth()) {
                if (firstAuth == null) {
                    firstAuth = auth;
                }
                if (auth.getId().equals(gitHubAuthId)) {
                    return auth;
                }
            }
            return firstAuth;
        }
        
        public List<GhprbGitHubAuth> getGithubAuth() {
            if (githubAuth == null || githubAuth.size() == 0) {
                githubAuth = new ArrayList<GhprbGitHubAuth>(1);
                githubAuth.add(new GhprbGitHubAuth(null, null, "Anonymous connection", null, null));
            }
            return githubAuth;
        }
        
        // map of jobs (by their fullName) and their map of pull requests
        private Map<String, ConcurrentMap<Integer, GhprbPullRequest>> jobs;
        
        public List<GhprbExtensionDescriptor> getExtensionDescriptors() {
            return GhprbExtensionDescriptor.allProject();
        }
        
        public List<GhprbExtensionDescriptor> getGlobalExtensionDescriptors() {
            return GhprbExtensionDescriptor.allGlobal();
        }
        
        private DescribableList<GhprbExtension, GhprbExtensionDescriptor> extensions;
        
        public DescribableList<GhprbExtension, GhprbExtensionDescriptor> getExtensions() {
            if (extensions == null) {
                extensions = new DescribableList<GhprbExtension, GhprbExtensionDescriptor>(Saveable.NOOP);
            }
            return extensions;
        }

        public DescriptorImpl() {
            load();
            readBackFromLegacy();
            if (jobs == null) {
                jobs = new HashMap<String, ConcurrentMap<Integer, GhprbPullRequest>>();
            }
//            save();
        }

        @Override
        public boolean isApplicable(Item item) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "GitHub Pull Request Comments";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            cron = formData.getString("cron");
            outputFile = formData.getString("outputFile");
            unstableAs = GHCommitState.valueOf(formData.getString("unstableAs"));
            autoCloseFailedPullRequests = formData.getBoolean("autoCloseFailedPullRequests");
            displayBuildErrorsOnDownstreamBuilds = formData.getBoolean("displayBuildErrorsOnDownstreamBuilds");
            
            githubAuth = req.bindJSONToList(GhprbGitHubAuth.class, formData.get("githubAuth"));
            
            extensions = new DescribableList<GhprbExtension, GhprbExtensionDescriptor>(Saveable.NOOP);

            try {
                extensions.rebuildHetero(req, formData, getGlobalExtensionDescriptors(), "extensions");
            } catch (IOException e) {
                e.printStackTrace();
            }
            
            readBackFromLegacy();

            save();
            return super.configure(req, formData);
        }
        

        public String getCron() {
            return cron;
        }

        public Boolean getAutoCloseFailedPullRequests() {
            return autoCloseFailedPullRequests;
        }

        public Boolean getDisplayBuildErrorsOnDownstreamBuilds() {
            return displayBuildErrorsOnDownstreamBuilds;
        }

        public GHCommitState getUnstableAs() {
            return unstableAs;
        }

        public String getOutputFile() {
            return outputFile;
        }

        public ConcurrentMap<Integer, GhprbPullRequest> getPullRequests(String projectName) {
            ConcurrentMap<Integer, GhprbPullRequest> ret;
            if (jobs.containsKey(projectName)) {
                Map<Integer, GhprbPullRequest> map = jobs.get(projectName);
                jobs.put(projectName, (ConcurrentMap<Integer, GhprbPullRequest>) map);
                ret = (ConcurrentMap<Integer, GhprbPullRequest>) map;
            } else {
                ret = new ConcurrentHashMap<Integer, GhprbPullRequest>();
                jobs.put(projectName, ret);
            }
            return ret;
        }

        @Deprecated
        private transient String publishedURL;
        @Deprecated
        private transient Integer logExcerptLines;
        @Deprecated
        private transient String commitStatusContext;
        @Deprecated
        private transient String accessToken;
        @Deprecated
        private transient String username;
        @Deprecated
        private transient String password;
        @Deprecated
        private transient String serverAPIUrl;
        
        public void readBackFromLegacy() {
            if (configVersion == null) {
                configVersion = 0;
            }
            
            if (logExcerptLines != null && logExcerptLines > 0) {
                addIfMissing(new GhprbBuildLog(logExcerptLines));
                logExcerptLines = null;
            }
            if (!StringUtils.isEmpty(publishedURL)) {
                addIfMissing(new GhprbPublishJenkinsUrl(publishedURL));
                publishedURL = null;
            }

            if (configVersion < 1) {
                GhprbSimpleStatus status = new GhprbSimpleStatus(commitStatusContext);
                addIfMissing(status);
                commitStatusContext = null;
            }
            
            if (!StringUtils.isEmpty(accessToken)) {
                try {
                    GhprbGitHubAuth auth = new GhprbGitHubAuth(serverAPIUrl, Ghprb.createCredentials(serverAPIUrl, accessToken), "Pre credentials Token", null, null);
                    if (githubAuth == null) {
                        githubAuth = new ArrayList<GhprbGitHubAuth>(1);
                    }
                    githubAuth.add(auth);
                    accessToken = null;
                    serverAPIUrl = null;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            
            if (!StringUtils.isEmpty(username) || !StringUtils.isEmpty(password)) {
                try {
                    GhprbGitHubAuth auth = new GhprbGitHubAuth(serverAPIUrl, Ghprb.createCredentials(serverAPIUrl, username, password), "Pre credentials username and password", null, null);
                    if (githubAuth == null) {
                        githubAuth = new ArrayList<GhprbGitHubAuth>(1);
                    }
                    githubAuth.add(auth);
                    username = null;
                    password = null;
                    serverAPIUrl = null;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            
            configVersion = 1;
        }
        

        private void addIfMissing(GhprbExtension ext) {
            if (getExtensions().get(ext.getClass()) == null) {
                getExtensions().add(ext);
            }
        }
        
    }

}
