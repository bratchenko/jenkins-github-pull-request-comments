package org.jenkinsci.plugins.ghprb;

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
import org.jenkinsci.plugins.ghprb.extensions.GhprbExtension;
import org.jenkinsci.plugins.ghprb.extensions.GhprbExtensionDescriptor;
import org.jenkinsci.plugins.ghprb.extensions.GhprbProjectExtension;
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
public class Ghprb {
    private static final Logger logger = Logger.getLogger(Ghprb.class.getName());
    private static final Pattern githubUserRepoPattern = Pattern.compile("^(http[s]?://[^/]*)/([^/]*)/([^/]*).*");

    private final GhprbTrigger trigger;
    private final AbstractProject<?, ?> project;
    private GhprbRepository repository;
    private GhprbBuilds builds;

    public Ghprb(AbstractProject<?, ?> project, GhprbTrigger trigger, ConcurrentMap<Integer, GhprbPullRequest> pulls) {
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

        this.repository = new GhprbRepository(user, repo, this, pulls);
        this.builds = new GhprbBuilds(trigger, repository);
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

    public GhprbBuilds getBuilds() {
        return builds;
    }

    public GhprbTrigger getTrigger() {
        return trigger;
    }

    public GhprbRepository getRepository() {
        return repository;
    }

    public GhprbGitHub getGitHub() {
        return new GhprbGitHub(trigger);
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
            state = GhprbTrigger.getDscp().getUnstableAs();
        } else {
            state = GHCommitState.FAILURE;
        }
        return state;
    }

    public static GhprbCause getCause(AbstractBuild<?, ?> build) {
        Cause cause = build.getCause(GhprbCause.class);
        if (cause == null) {
            return null;
        }
        return (GhprbCause) cause;
    }
    
    public static GhprbTrigger extractTrigger(AbstractBuild<?, ?> build) {
        return extractTrigger(build.getProject());
    }

    public static GhprbTrigger extractTrigger(AbstractProject<?, ?> p) {
        GhprbTrigger trigger = p.getTrigger(GhprbTrigger.class);
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
    
    public static void filterList(DescribableList<GhprbExtension, GhprbExtensionDescriptor> descriptors, Predicate predicate) {
        for (GhprbExtension descriptor : descriptors) {
            if (!predicate.evaluate(descriptor)) {
                descriptors.remove(descriptor);
            }
        }
    }
    
    private static DescribableList<GhprbExtension, GhprbExtensionDescriptor> copyExtensions(DescribableList<GhprbExtension, GhprbExtensionDescriptor> ...extensionsList){
        DescribableList<GhprbExtension, GhprbExtensionDescriptor> copiedList = new DescribableList<GhprbExtension, GhprbExtensionDescriptor>(Saveable.NOOP);
        for (DescribableList<GhprbExtension, GhprbExtensionDescriptor> extensions: extensionsList) {
            copiedList.addAll(extensions);
        }
        return copiedList;
    }

    @SuppressWarnings("unchecked")
    public static DescribableList<GhprbExtension, GhprbExtensionDescriptor> getJobExtensions(GhprbTrigger trigger, Class<?> ...types) {
        
        // First get all global extensions
        DescribableList<GhprbExtension, GhprbExtensionDescriptor> copied = copyExtensions(trigger.getDescriptor().getExtensions());
        
        // Remove extensions that are specified by job
        filterList(copied, PredicateUtils.notPredicate(InstanceofPredicate.getInstance(GhprbProjectExtension.class)));
        
        // Then get the rest of the extensions from the job
        copied = copyExtensions(copied, trigger.getExtensions());
        
        // Filter extensions by desired interface
        filterList(copied, PredicateUtils.anyPredicate(createPredicate(types)));
        return copied;
    }
    
    public static DescribableList<GhprbExtension, GhprbExtensionDescriptor> onlyOneEntry(DescribableList<GhprbExtension, GhprbExtensionDescriptor> extensions, Class<?> ...types) {
        DescribableList<GhprbExtension, GhprbExtensionDescriptor> copyExtensions = new DescribableList<GhprbExtension, GhprbExtensionDescriptor>(Saveable.NOOP);
        
        Set<Class<?>> extSet = new HashSet<Class<?>>(types.length);
        List<Predicate> predicates = createPredicate(types);
        for (GhprbExtension extension: extensions) {
            if (addExtension(extension, predicates, extSet)) {
                copyExtensions.add(extension);
            }
        }
        
        return copyExtensions;
    }
    
    private static boolean addExtension(GhprbExtension extension, List<Predicate> predicates, Set<Class<?>> extSet) {
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
    
    public static void addIfMissing(DescribableList<GhprbExtension, GhprbExtensionDescriptor> extensions, GhprbExtension ext, Class<?> type) {
        Predicate predicate = InstanceofPredicate.getInstance(type);
        for (GhprbExtension extension : extensions) {
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
