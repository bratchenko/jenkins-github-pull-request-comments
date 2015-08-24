package org.jenkinsci.plugins.ghprc;

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
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.ghprc.extensions.GhprcCommitStatus;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtension;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtensionDescriptor;
import org.jenkinsci.plugins.ghprc.extensions.comments.GhprcBuildLog;
import org.jenkinsci.plugins.ghprc.extensions.comments.GhprcPublishJenkinsUrl;
import org.jenkinsci.plugins.ghprc.extensions.status.GhprcSimpleStatus;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GitHub;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
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

/**
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public class GhprcTrigger extends GhprcTriggerBackwardsCompatible {

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    private static final Logger logger = Logger.getLogger(GhprcTrigger.class.getName());
    private final String cron;
    private final String buildDescTemplate;
    private final Boolean useGitHubHooks;
    private Boolean autoCloseFailedPullRequests;
    private Boolean displayBuildErrorsOnDownstreamBuilds;
    private transient Ghprc helper;
    private String project;
    private AbstractProject<?, ?> _project;
    private String gitHubAuthId;
    
    
    private DescribableList<GhprcExtension, GhprcExtensionDescriptor> extensions = new DescribableList<GhprcExtension, GhprcExtensionDescriptor>(Saveable.NOOP);
    
    public DescribableList<GhprcExtension, GhprcExtensionDescriptor> getExtensions() {
        if (extensions == null) {
            extensions = new DescribableList<GhprcExtension, GhprcExtensionDescriptor>(Saveable.NOOP,Util.fixNull(extensions));
            extensions.add(new GhprcSimpleStatus());
        }
        return extensions;
    }
    
    private void setExtensions(List<GhprcExtension> extensions) {
        DescribableList<GhprcExtension, GhprcExtensionDescriptor> rawList = new DescribableList<GhprcExtension, GhprcExtensionDescriptor>(
                Saveable.NOOP,Util.fixNull(extensions));
        
        // Filter out items that we only want one of, like the status updater.
        this.extensions = Ghprc.onlyOneEntry(rawList,
                GhprcCommitStatus.class
        );
        
        // Now make sure we have at least one of the types we need one of.
        Ghprc.addIfMissing(this.extensions, new GhprcSimpleStatus(), GhprcCommitStatus.class);
    }

    @DataBoundConstructor
    public GhprcTrigger(String cron,
                        Boolean useGitHubHooks,
                        Boolean autoCloseFailedPullRequests,
                        Boolean displayBuildErrorsOnDownstreamBuilds,
                        String gitHubAuthId,
                        String buildDescTemplate,
                        List<GhprcExtension> extensions) throws ANTLRException {
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
            logger.log(Level.INFO, "GitHub project property is missing the URL, cannot start ghprc trigger for job " + this.project);
            return;
        }
        try {
            helper = createGhprc(project);
        } catch (IllegalStateException ex) {
            logger.log(Level.SEVERE, "Can't start ghprc trigger", ex);
            return;
        }

        logger.log(Level.INFO, "Starting the ghprc trigger for the {0} job; newInstance is {1}",
                new String[] { this.project, String.valueOf(newInstance) });
        helper.init();
    }

    Ghprc createGhprc(AbstractProject<?, ?> project) {
        return new Ghprc(project, this, getDescriptor().getPullRequests(project.getFullName()));
    }

    @Override
    public void stop() {
        logger.log(Level.INFO, "Stopping the ghprc trigger for project {0}", this.project);
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

    public QueueTaskFuture<?> startJob(GhprcCause cause, GhprcRepository repo) {
        ArrayList<ParameterValue> values = getDefaultParameters();
        final String commitSha = cause.isMerged() ? "origin/pr/" + cause.getPullID() + "/merge" : cause.getCommit();
        values.add(new StringParameterValue("sha1", commitSha));
        values.add(new StringParameterValue("ghprcActualCommit", cause.getCommit()));

        setCommitAuthor(cause, values);

        final StringParameterValue pullIdPv = new StringParameterValue("ghprcPullId", String.valueOf(cause.getPullID()));
        values.add(pullIdPv);
        values.add(new StringParameterValue("ghprcTargetBranch", String.valueOf(cause.getTargetBranch())));
        values.add(new StringParameterValue("ghprcSourceBranch", String.valueOf(cause.getSourceBranch())));
        values.add(new StringParameterValue("GIT_BRANCH", String.valueOf(cause.getSourceBranch())));
        // it's possible the GHUser doesn't have an associated email address
        values.add(new StringParameterValue("ghprcPullAuthorEmail", getString(cause.getAuthorEmail(), "")));
        values.add(new StringParameterValue("ghprcPullDescription", String.valueOf(cause.getShortDescription())));
        values.add(new StringParameterValue("ghprcPullTitle", String.valueOf(cause.getTitle())));
        values.add(new StringParameterValue("ghprcPullLink", String.valueOf(cause.getUrl())));
        values.add(new StringParameterValue("ghprcOutputFile", getDescriptor().getOutputFile()));

        try {
            values.add(new StringParameterValue("ghprcTargetCommit",
                    repo.getGitHubRepo().getBranches().get(cause.getTargetBranch()).getSHA1()));
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable to get branches from github repo", e);
        }

        // add the previous pr BuildData as an action so that the correct change log is generated by the GitSCM plugin
        // note that this will be removed from the Actions list after the job is completed so that the old (and incorrect)
        // one isn't there
        return this.job.scheduleBuild2(job.getQuietPeriod(), cause, new ParametersAction(values), findPreviousBuildForPullId(pullIdPv));
    }
    
    
    public GhprcGitHubAuth getGitHubApiAuth() {
        if (gitHubAuthId == null) {
            for (GhprcGitHubAuth auth: getDescriptor().getGithubAuth()){
                gitHubAuthId = auth.getId();
                getDescriptor().save();
                return auth;
            }
        }
        return getDescriptor().getGitHubAuth(gitHubAuthId);
    }
    

    public GitHub getGitHub() throws IOException {
        GhprcGitHubAuth auth = getGitHubApiAuth();
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

    private void setCommitAuthor(GhprcCause cause, ArrayList<ParameterValue> values) {
        String authorName = "";
        String authorEmail = "";
        if (cause.getCommitAuthor() != null) {
            authorName = getString(cause.getCommitAuthor().getName(), "");
            authorEmail = getString(cause.getCommitAuthor().getEmail(), "");
        }

        values.add(new StringParameterValue("ghprcActualCommitAuthor", authorName));
        values.add(new StringParameterValue("ghprcActualCommitAuthorEmail", authorEmail));
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

    public GhprcWebHook getWebHook() {
        GhprcWebHook webHook = new GhprcWebHook(this);
        return webHook;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @VisibleForTesting
    void setHelper(Ghprc helper) {
        this.helper = helper;
    }

    public GhprcBuilds getBuilds() {
        if (helper == null) {
            logger.log(Level.SEVERE, "The ghprc trigger for {0} wasn''t properly started - helper is null", this.project);
            return null;
        }
        return helper.getBuilds();
    }

    public GhprcRepository getRepository() {
        if (helper == null) {
            logger.log(Level.SEVERE, "The ghprc trigger for {0} wasn''t properly started - helper is null", this.project);
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
        
        private List<GhprcGitHubAuth> githubAuth;
        
        public GhprcGitHubAuth getGitHubAuth(String gitHubAuthId) {
            
            if (gitHubAuthId == null) {
                return getGithubAuth().get(0);
            }
            
            GhprcGitHubAuth firstAuth = null;
            for (GhprcGitHubAuth auth : getGithubAuth()) {
                if (firstAuth == null) {
                    firstAuth = auth;
                }
                if (auth.getId().equals(gitHubAuthId)) {
                    return auth;
                }
            }
            return firstAuth;
        }
        
        public List<GhprcGitHubAuth> getGithubAuth() {
            if (githubAuth == null || githubAuth.size() == 0) {
                githubAuth = new ArrayList<GhprcGitHubAuth>(1);
                githubAuth.add(new GhprcGitHubAuth(null, null, "Anonymous connection", null, null));
            }
            return githubAuth;
        }
        
        // map of jobs (by their fullName) and their map of pull requests
        private Map<String, ConcurrentMap<Integer, GhprcPullRequest>> jobs;
        
        public List<GhprcExtensionDescriptor> getExtensionDescriptors() {
            return GhprcExtensionDescriptor.allProject();
        }
        
        public List<GhprcExtensionDescriptor> getGlobalExtensionDescriptors() {
            return GhprcExtensionDescriptor.allGlobal();
        }
        
        private DescribableList<GhprcExtension, GhprcExtensionDescriptor> extensions;
        
        public DescribableList<GhprcExtension, GhprcExtensionDescriptor> getExtensions() {
            if (extensions == null) {
                extensions = new DescribableList<GhprcExtension, GhprcExtensionDescriptor>(Saveable.NOOP);
            }
            return extensions;
        }

        public DescriptorImpl() {
            load();
            readBackFromLegacy();
            if (jobs == null) {
                jobs = new HashMap<String, ConcurrentMap<Integer, GhprcPullRequest>>();
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

        @SuppressWarnings("UnusedDeclaration")
        public ListBoxModel doFillUnstableAsItems() {
            ListBoxModel items = new ListBoxModel();
            GHCommitState[] results = new GHCommitState[] {GHCommitState.SUCCESS,GHCommitState.ERROR,GHCommitState.FAILURE};
            for (GHCommitState nextResult : results) {
                String text = StringUtils.capitalize(nextResult.toString().toLowerCase());
                items.add(text, nextResult.toString());
                if (unstableAs.toString().equals(nextResult)) {
                    items.get(items.size()-1).selected = true;
                }
            }

            return items;
        }


        @SuppressWarnings("UnusedDeclaration")
        public ListBoxModel doFillGitHubAuthIdItems(@QueryParameter("gitHubAuthId") String gitHubAuthId) {
            ListBoxModel model = new ListBoxModel();
            for (GhprcGitHubAuth auth : getGithubAuth()) {
                String description = Util.fixNull(auth.getDescription());
                int length = description.length();
                length = length > 50 ? 50 : length;
                ListBoxModel.Option next = new ListBoxModel.Option(auth.getServerAPIUrl() + " : " + description.substring(0, length), auth.getId());
                if (!StringUtils.isEmpty(gitHubAuthId) && gitHubAuthId.equals(auth.getId())) {
                    next.selected = true;
                }
                model.add(next);
            }
            return model;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            cron = formData.getString("cron");
            outputFile = formData.getString("outputFile");
            unstableAs = GHCommitState.valueOf(formData.getString("unstableAs"));
            autoCloseFailedPullRequests = formData.getBoolean("autoCloseFailedPullRequests");
            displayBuildErrorsOnDownstreamBuilds = formData.getBoolean("displayBuildErrorsOnDownstreamBuilds");
            
            githubAuth = req.bindJSONToList(GhprcGitHubAuth.class, formData.get("githubAuth"));
            
            extensions = new DescribableList<GhprcExtension, GhprcExtensionDescriptor>(Saveable.NOOP);

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

        public ConcurrentMap<Integer, GhprcPullRequest> getPullRequests(String projectName) {
            ConcurrentMap<Integer, GhprcPullRequest> ret;
            if (jobs.containsKey(projectName)) {
                Map<Integer, GhprcPullRequest> map = jobs.get(projectName);
                jobs.put(projectName, (ConcurrentMap<Integer, GhprcPullRequest>) map);
                ret = (ConcurrentMap<Integer, GhprcPullRequest>) map;
            } else {
                ret = new ConcurrentHashMap<Integer, GhprcPullRequest>();
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
                addIfMissing(new GhprcBuildLog(logExcerptLines));
                logExcerptLines = null;
            }
            if (!StringUtils.isEmpty(publishedURL)) {
                addIfMissing(new GhprcPublishJenkinsUrl(publishedURL));
                publishedURL = null;
            }

            if (configVersion < 1) {
                GhprcSimpleStatus status = new GhprcSimpleStatus(commitStatusContext);
                addIfMissing(status);
                commitStatusContext = null;
            }
            
            if (!StringUtils.isEmpty(accessToken)) {
                try {
                    GhprcGitHubAuth auth = new GhprcGitHubAuth(serverAPIUrl, Ghprc.createCredentials(serverAPIUrl, accessToken), "Pre credentials Token", null, null);
                    if (githubAuth == null) {
                        githubAuth = new ArrayList<GhprcGitHubAuth>(1);
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
                    GhprcGitHubAuth auth = new GhprcGitHubAuth(serverAPIUrl, Ghprc.createCredentials(serverAPIUrl, username, password), "Pre credentials username and password", null, null);
                    if (githubAuth == null) {
                        githubAuth = new ArrayList<GhprcGitHubAuth>(1);
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
        

        private void addIfMissing(GhprcExtension ext) {
            if (getExtensions().get(ext.getClass()) == null) {
                getExtensions().add(ext);
            }
        }
        
    }

}
