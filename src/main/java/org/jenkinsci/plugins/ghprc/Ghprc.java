package org.jenkinsci.plugins.ghprc;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainSpecification;
import com.cloudbees.plugins.credentials.domains.HostnamePortSpecification;
import com.cloudbees.plugins.credentials.domains.HostnameSpecification;
import com.cloudbees.plugins.credentials.domains.PathSpecification;
import com.cloudbees.plugins.credentials.domains.SchemeSpecification;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.coravy.hudson.plugins.github.GithubProjectProperty;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.DescribableList;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.apache.commons.collections.Predicate;
import org.apache.commons.collections.PredicateUtils;
import org.apache.commons.collections.functors.InstanceofPredicate;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtension;
import org.jenkinsci.plugins.ghprc.extensions.GhprcExtensionDescriptor;
import org.jenkinsci.plugins.ghprc.extensions.GhprcProjectExtension;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.kohsuke.github.GHCommitState;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author janinko
 */
public class Ghprc {
    private static final Logger logger = Logger.getLogger(Ghprc.class.getName());
    private static final Pattern githubUserRepoPattern = Pattern.compile("^(http[s]?://[^/]*)/([^/]*)/([^/]*).*");

    private final GhprcTrigger trigger;
    private final AbstractProject<?, ?> project;
    private GhprcRepository repository;
    private GhprcBuilds builds;

    public Ghprc(AbstractProject<?, ?> project, GhprcTrigger trigger, ConcurrentMap<Integer, GhprcPullRequest> pulls) {
        this.project = project;

        final GithubProjectProperty ghpp = project.getProperty(GithubProjectProperty.class);
        if (ghpp == null || ghpp.getProjectUrl() == null) {
            throw new IllegalStateException("A GitHub project url is required.");
        }
        String baseUrl = ghpp.getProjectUrl().baseUrl();
        Matcher m = githubUserRepoPattern.matcher(baseUrl);
        if (!m.matches()) {
            throw new IllegalStateException(String.format("Invalid GitHub project url: %s", baseUrl));
        }
        final String user = m.group(2);
        final String repo = m.group(3);

        this.trigger = trigger;

        this.repository = new GhprcRepository(user, repo, this, pulls);
        this.builds = new GhprcBuilds(trigger, repository);
    }

    public void init() {
        this.repository.init();
        if (trigger.getUseGitHubHooks()) {
            this.repository.createHook();
        }
    }

    public boolean isProjectDisabled() {
        return project.isDisabled();
    }

    public GhprcBuilds getBuilds() {
        return builds;
    }

    public GhprcTrigger getTrigger() {
        return trigger;
    }

    public GhprcRepository getRepository() {
        return repository;
    }

    public GhprcGitHub getGitHub() {
        return new GhprcGitHub(trigger);
    }

    void run() {
        repository.check();
    }

    void stop() {
        repository = null;
        builds = null;
    }

    public static String replaceMacros(AbstractBuild<?, ?> build, TaskListener listener, String inputString) {
        String returnString = inputString;
        if (build != null && inputString != null) {
            try {
                Map<String, String> messageEnvVars = new HashMap<String, String>();

                messageEnvVars.putAll(build.getCharacteristicEnvVars());
                messageEnvVars.putAll(build.getBuildVariables());
                messageEnvVars.putAll(build.getEnvironment(listener));

                returnString = Util.replaceMacro(inputString, messageEnvVars);

            } catch (Exception e) {
                logger.log(Level.SEVERE, "Couldn't replace macros in message: ", e);
            }
        }
        return returnString;
    }
    

    public static String replaceMacros(AbstractProject<?, ?> project, String inputString) {
        String returnString = inputString;
        if (project != null && inputString != null) {
            try {
                Map<String, String> messageEnvVars = new HashMap<String, String>();

                messageEnvVars.putAll(project.getCharacteristicEnvVars());

                returnString = Util.replaceMacro(inputString, messageEnvVars);

            } catch (Exception e) {
                logger.log(Level.SEVERE, "Couldn't replace macros in message: ", e);
            }
        }
        return returnString;
    }
    
    public static GHCommitState getState(AbstractBuild<?, ?> build) {

        GHCommitState state;
        if (build.getResult() == Result.SUCCESS) {
            state = GHCommitState.SUCCESS;
        } else if (build.getResult() == Result.UNSTABLE) {
            state = GhprcTrigger.getDscp().getUnstableAs();
        } else {
            state = GHCommitState.FAILURE;
        }
        return state;
    }

    public static GhprcCause getCause(AbstractBuild<?, ?> build) {
        Cause cause = build.getCause(GhprcCause.class);
        if (cause == null) {
            return null;
        }
        return (GhprcCause) cause;
    }
    
    public static GhprcTrigger extractTrigger(AbstractBuild<?, ?> build) {
        return extractTrigger(build.getProject());
    }

    public static GhprcTrigger extractTrigger(AbstractProject<?, ?> p) {
        GhprcTrigger trigger = p.getTrigger(GhprcTrigger.class);
        if (trigger == null) {
            return null;
        }
        return trigger;
    }
    
    private static List<Predicate> createPredicate(Class<?> ...types) {
        List<Predicate> predicates = new ArrayList<Predicate>(types.length);
        for (Class<?> type : types) {
            predicates.add(InstanceofPredicate.getInstance(type));
        }
        return predicates;
    }
    
    public static void filterList(DescribableList<GhprcExtension, GhprcExtensionDescriptor> descriptors, Predicate predicate) {
        for (GhprcExtension descriptor : descriptors) {
            if (!predicate.evaluate(descriptor)) {
                descriptors.remove(descriptor);
            }
        }
    }
    
    private static DescribableList<GhprcExtension, GhprcExtensionDescriptor> copyExtensions(DescribableList<GhprcExtension, GhprcExtensionDescriptor> ...extensionsList){
        DescribableList<GhprcExtension, GhprcExtensionDescriptor> copiedList = new DescribableList<GhprcExtension, GhprcExtensionDescriptor>(Saveable.NOOP);
        for (DescribableList<GhprcExtension, GhprcExtensionDescriptor> extensions: extensionsList) {
            copiedList.addAll(extensions);
        }
        return copiedList;
    }

    @SuppressWarnings("unchecked")
    public static DescribableList<GhprcExtension, GhprcExtensionDescriptor> getJobExtensions(GhprcTrigger trigger, Class<?> ...types) {
        
        // First get all global extensions
        DescribableList<GhprcExtension, GhprcExtensionDescriptor> copied = copyExtensions(trigger.getDescriptor().getExtensions());
        
        // Remove extensions that are specified by job
        filterList(copied, PredicateUtils.notPredicate(InstanceofPredicate.getInstance(GhprcProjectExtension.class)));
        
        // Then get the rest of the extensions from the job
        copied = copyExtensions(copied, trigger.getExtensions());
        
        // Filter extensions by desired interface
        filterList(copied, PredicateUtils.anyPredicate(createPredicate(types)));
        return copied;
    }
    
    public static DescribableList<GhprcExtension, GhprcExtensionDescriptor> onlyOneEntry(DescribableList<GhprcExtension, GhprcExtensionDescriptor> extensions, Class<?> ...types) {
        DescribableList<GhprcExtension, GhprcExtensionDescriptor> copyExtensions = new DescribableList<GhprcExtension, GhprcExtensionDescriptor>(Saveable.NOOP);
        
        Set<Class<?>> extSet = new HashSet<Class<?>>(types.length);
        List<Predicate> predicates = createPredicate(types);
        for (GhprcExtension extension: extensions) {
            if (addExtension(extension, predicates, extSet)) {
                copyExtensions.add(extension);
            }
        }
        
        return copyExtensions;
    }
    
    private static boolean addExtension(GhprcExtension extension, List<Predicate> predicates, Set<Class<?>> extSet) {
        for (Predicate predicate: predicates) {
            if (predicate.evaluate(extension)) {
                Class<?> clazz = ((InstanceofPredicate)predicate).getType();
                if (extSet.contains(clazz)) {
                    return false;
                } else {
                    extSet.add(clazz);
                    return true;
                }
            }
        }
        return true;
    }
    
    public static void addIfMissing(DescribableList<GhprcExtension, GhprcExtensionDescriptor> extensions, GhprcExtension ext, Class<?> type) {
        Predicate predicate = InstanceofPredicate.getInstance(type);
        for (GhprcExtension extension : extensions) {
            if (predicate.evaluate(extension)){
                return;
            }
        }
        extensions.add(ext);
    }

    public static StandardCredentials lookupCredentials(Item context, String credentialId, String uri) {
        String contextName = "(Jenkins.instance)";
        if (context != null) {
            contextName = context.getFullName();
        }
        logger.log(Level.FINE, "Looking up credentials for {0}, using context {1} for url {2}", new Object[] { credentialId, contextName, uri });
        
        List<StandardCredentials> credentials;
        
        if (context == null) {
            credentials = CredentialsProvider.lookupCredentials(StandardCredentials.class, Jenkins.getInstance(), ACL.SYSTEM,
                    URIRequirementBuilder.fromUri(uri).build());
        } else {
            credentials = CredentialsProvider.lookupCredentials(StandardCredentials.class, context, ACL.SYSTEM,
                    URIRequirementBuilder.fromUri(uri).build());
        }
        
        logger.log(Level.FINE, "Found {0} credentials", new Object[]{credentials.size()});
        
        return (credentialId == null) ? null : CredentialsMatchers.firstOrNull(credentials,
                    CredentialsMatchers.withId(credentialId));
    }
    
    public static String createCredentials(String serverAPIUrl, String token) throws Exception {
        String description = serverAPIUrl + " GitHub auto generated token credentials";
        StringCredentialsImpl credentials = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, 
                UUID.randomUUID().toString(), 
                description, 
                Secret.fromString(token));
        return createCredentials(serverAPIUrl, credentials);
    }
    
    public static String createCredentials(String serverAPIUrl, String username, String password) throws Exception {
        String description = serverAPIUrl + " GitHub auto generated Username password credentials";
        UsernamePasswordCredentialsImpl credentials = new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, 
                UUID.randomUUID().toString(),
                description,
                username,
                password);
        return createCredentials(serverAPIUrl, credentials);
    }
    
    private static String createCredentials(String serverAPIUrl, StandardCredentials credentials) throws Exception {
        List<DomainSpecification> specifications = new ArrayList<DomainSpecification>(2);
        
        URI serverUri = new URI(serverAPIUrl);
        
        if (serverUri.getPort() > 0) {
            specifications.add(new HostnamePortSpecification(serverUri.getHost() + ":" + serverUri.getPort(), null));
        } else {
            specifications.add(new HostnameSpecification(serverUri.getHost(), null));
        }
        
        specifications.add(new SchemeSpecification(serverUri.getScheme()));
        String path = serverUri.getPath();
        if (StringUtils.isEmpty(path)) {
            path = "/";
        }
        specifications.add(new PathSpecification(path, null, false));
        
        Domain domain = new Domain(serverUri.getHost(), "Auto generated credentials domain", specifications);
        CredentialsStore provider = new SystemCredentialsProvider.StoreImpl();
        provider.addDomain(domain, credentials);
        return credentials.getId();
    }
}
