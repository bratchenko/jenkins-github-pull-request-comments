package org.jenkinsci.plugins.ghprb;

import com.coravy.hudson.plugins.github.GithubProjectProperty;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ItemGroup;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.StreamBuildListener;
import org.jenkinsci.plugins.ghprb.GhprbTrigger.DescriptorImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHPullRequestCommitDetail.Commit;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitUser;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.PagedIterator;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

@RunWith(MockitoJUnitRunner.class)
public class GhprbPullRequestMergeTest {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    private FreeStyleProject project = mock(FreeStyleProject.class);
    private AbstractBuild<?, ?> build = mock(FreeStyleBuild.class);

    @Mock
    private GhprbPullRequest pullRequest;
    @Mock
    private GHPullRequest pr;

    @Mock
    private GitUser committer;
    @Mock
    private GHUser triggerSender;
    @Mock
    private GhprbCause cause;
    @Mock
    private Ghprb helper;
    @Mock
    private GhprbRepository repo;

    @Mock
    private StreamBuildListener listener;

    @Mock
    private ItemGroup<?> parent;

    private final String adminLogin = "admin";

    private final String committerName = "committer";

    @Before
    public void beforeTest() throws Exception {
        Map<String, Object> triggerValues = new HashMap<String, Object>(10);

        GhprbTrigger trigger = spy(GhprbTestUtil.getTrigger(triggerValues));

        ConcurrentMap<Integer, GhprbPullRequest> pulls = new ConcurrentHashMap<Integer, GhprbPullRequest>(1);
        Integer pullId = 1;
        pulls.put(pullId, pullRequest);
        Map<String, ConcurrentMap<Integer, GhprbPullRequest>> jobs = new HashMap<String, ConcurrentMap<Integer, GhprbPullRequest>>(1);
        jobs.put("project", pulls);

        GithubProjectProperty projectProperty = new GithubProjectProperty("https://github.com/jenkinsci/ghprb-plugin");
        DescriptorImpl descriptor = trigger.getDescriptor();

        PrintStream logger = mock(PrintStream.class);

        given(parent.getFullName()).willReturn("");

        given(project.getParent()).willReturn(parent);
        given(project.getTrigger(GhprbTrigger.class)).willReturn(trigger);
        given(project.getName()).willReturn("project");
        given(project.getProperty(GithubProjectProperty.class)).willReturn(projectProperty);
        given(project.isDisabled()).willReturn(false);

        given(build.getCause(GhprbCause.class)).willReturn(cause);
        given(build.getResult()).willReturn(Result.SUCCESS);
        given(build.getParent()).willCallRealMethod();

        given(pullRequest.getPullRequest()).willReturn(pr);

        given(cause.getPullID()).willReturn(pullId);
        given(cause.isMerged()).willReturn(true);
        given(cause.getCommitAuthor()).willReturn(committer);

        given(listener.getLogger()).willReturn(logger);

        doNothing().when(repo).addOrUpdateComment(anyInt(), anyString(), anyString(), null, null);
        doNothing().when(logger).println();

        Field parentField = Run.class.getDeclaredField("project");
        parentField.setAccessible(true);
        parentField.set(build, project);

        Field jobsField = descriptor.getClass().getDeclaredField("jobs");
        jobsField.setAccessible(true);
        jobsField.set(descriptor, jobs);

        helper = spy(new Ghprb(project, trigger, pulls));
        trigger.setHelper(helper);
        given(helper.getRepository()).willReturn(repo);
    }

    @After
    public void afterClass() {

    }

    @SuppressWarnings("unchecked")
    private void setupConditions(String triggerLogin, String committerName) throws IOException {
        given(triggerSender.getLogin()).willReturn(triggerLogin);
        given(triggerSender.getName()).willReturn(committerName);
        given(committer.getName()).willReturn(this.committerName);

        PagedIterator<GHPullRequestCommitDetail> itr = Mockito.mock(PagedIterator.class);
        PagedIterable<GHPullRequestCommitDetail> pagedItr = Mockito.mock(PagedIterable.class);

        Commit commit = mock(Commit.class);
        GHPullRequestCommitDetail commitDetail = mock(GHPullRequestCommitDetail.class);

        given(pr.listCommits()).willReturn(pagedItr);

        given(pagedItr.iterator()).willReturn(itr);

        given(itr.hasNext()).willReturn(true, false);
        given(itr.next()).willReturn(commitDetail);

        given(commitDetail.getCommit()).willReturn(commit);
        given(commit.getCommitter()).willReturn(committer);
    }

    private GhprbPullRequestMerge setupMerger() {
        String mergeComment = "merge";
        GhprbPullRequestMerge merger = spy(new GhprbPullRequestMerge(mergeComment));

        merger.setHelper(helper);

        return merger;
    }

    @Test
    public void testTriggerMerge() throws Exception {

        @SuppressWarnings("ConstantConditions") GhprbPullRequestMerge merger = setupMerger();

        setupConditions(adminLogin, committerName);
        assertThat(merger.perform(build, null, listener)).isEqualTo(false);
    }

    @Test
    public void testDenyMerge() throws Exception {

        @SuppressWarnings("ConstantConditions") GhprbPullRequestMerge merger = setupMerger();

        String nonCommitterName = "noncommitter";
        setupConditions(adminLogin, nonCommitterName);
        assertThat(merger.perform(build, null, listener)).isEqualTo(false);

        String nonAdminLogin = "nonadmin";
        setupConditions(nonAdminLogin, nonCommitterName);
        assertThat(merger.perform(build, null, listener)).isEqualTo(false);

        setupConditions(adminLogin, committerName);
        assertThat(merger.perform(build, null, listener)).isEqualTo(false);

        setupConditions(nonAdminLogin, committerName);
        assertThat(merger.perform(build, null, listener)).isEqualTo(false);
    }

}
