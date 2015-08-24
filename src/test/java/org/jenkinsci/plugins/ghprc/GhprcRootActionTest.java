package org.jenkinsci.plugins.ghprc;

import hudson.model.FreeStyleProject;
import hudson.plugins.git.GitSCM;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URLEncoder;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRateLimit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.stapler.StaplerRequest;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.coravy.hudson.plugins.github.GithubProjectProperty;

import static com.google.common.collect.Lists.newArrayList;
import static org.fest.assertions.Assertions.assertThat;
import static org.kohsuke.github.GHIssueState.OPEN;
import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class GhprcRootActionTest {
    

    @Mock
    protected GHCommitPointer commitPointer;
    @Mock
    protected GHPullRequest ghPullRequest;
    @Mock
    protected GhprcGitHub ghprcGitHub;
    @Mock
    protected GHRepository ghRepository;
    @Mock
    protected GHUser ghUser;
    
    protected GitHub gitHub;
    // Stubs
    protected GHRateLimit ghRateLimit = new GHRateLimit();


    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Mock
    private StaplerRequest req;

    private BufferedReader br;

    @Before
    public void setup() throws Exception {
        gitHub = spy(GitHub.connectAnonymously());
        given(ghprcGitHub.get()).willReturn(gitHub);
        given(gitHub.getRateLimit()).willReturn(ghRateLimit);
        doReturn(ghRepository).when(gitHub).getRepository(anyString());
        given(commitPointer.getRef()).willReturn("ref");
        given(ghRepository.getName()).willReturn("dropwizard");

        GhprcTestUtil.mockPR(ghPullRequest, commitPointer, new DateTime(), new DateTime().plusDays(1));

        given(ghRepository.getPullRequests(eq(OPEN))).willReturn(newArrayList(ghPullRequest)).willReturn(newArrayList(ghPullRequest));

        given(ghPullRequest.getUser()).willReturn(ghUser);
        given(ghUser.getEmail()).willReturn("email@email.com");
        given(ghUser.getLogin()).willReturn("user");

        ghRateLimit.remaining = GhprcTestUtil.INITIAL_RATE_LIMIT;

        GhprcTestUtil.mockCommitList(ghPullRequest);
    }

    @Test
    public void testUrlEncoded() throws Exception {
        // GIVEN
        FreeStyleProject project = jenkinsRule.createFreeStyleProject("testUrlEncoded");
        GhprcTrigger trigger = spy(GhprcTestUtil.getTrigger(null));
        given(commitPointer.getSha()).willReturn("sha1");
        GhprcTestUtil.setupGhprcTriggerDescriptor(null);
        project.addProperty(new GithubProjectProperty("https://github.com/user/dropwizard"));
        given(ghPullRequest.getNumber()).willReturn(1);
        Ghprc ghprc = spy(trigger.createGhprc(project));
        doReturn(ghprcGitHub).when(ghprc).getGitHub();
        trigger.start(project, true);
        trigger.setHelper(ghprc);
        ghprc.getRepository().setHelper(ghprc);
        project.addTrigger(trigger);
        GitSCM scm = GhprcTestUtil.provideGitSCM();
        project.setScm(scm);

        GhprcTestUtil.triggerRunAndWait(10, trigger, project);

        assertThat(project.getBuilds().toArray().length).isEqualTo(1);

		doReturn(gitHub).when(trigger).getGitHub();

        BufferedReader br = new BufferedReader(new StringReader(
                "payload=" + URLEncoder.encode(GhprcTestUtil.PAYLOAD, "UTF-8")));

        given(req.getContentType()).willReturn("application/x-www-form-urlencoded");
        given(req.getParameter("payload")).willReturn(GhprcTestUtil.PAYLOAD);
        given(req.getHeader("X-GitHub-Event")).willReturn("issue_comment");
        given(req.getReader()).willReturn(br);
        given(req.getCharacterEncoding()).willReturn("UTF-8");

        GhprcRootAction ra = new GhprcRootAction();
        ra.doIndex(req, null);
        GhprcTestUtil.waitForBuildsToFinish(project);

        assertThat(project.getBuilds().toArray().length).isEqualTo(2);
    }
    
    @Test
    public void disabledJobsDontBuild() throws Exception {
        // GIVEN
        FreeStyleProject project = jenkinsRule.createFreeStyleProject("disabledJobsDontBuild");
        GhprcTrigger trigger = spy(GhprcTestUtil.getTrigger(null));
        given(commitPointer.getSha()).willReturn("sha1");
        GhprcTestUtil.setupGhprcTriggerDescriptor(null);
        project.addProperty(new GithubProjectProperty("https://github.com/user/dropwizard"));
        given(ghPullRequest.getNumber()).willReturn(1);
        Ghprc ghprc = spy(trigger.createGhprc(project));
        doReturn(ghprcGitHub).when(ghprc).getGitHub();
        trigger.start(project, true);
        trigger.setHelper(ghprc);
        ghprc.getRepository().setHelper(ghprc);
        project.addTrigger(trigger);
        GitSCM scm = GhprcTestUtil.provideGitSCM();
        project.setScm(scm);

        GhprcTestUtil.triggerRunAndWait(10, trigger, project);

        assertThat(project.getBuilds().toArray().length).isEqualTo(1);
        
        project.disable();

		doReturn(gitHub).when(trigger).getGitHub();

        BufferedReader br = new BufferedReader(new StringReader(
                "payload=" + URLEncoder.encode(GhprcTestUtil.PAYLOAD, "UTF-8")));

        given(req.getContentType()).willReturn("application/x-www-form-urlencoded");
        given(req.getParameter("payload")).willReturn(GhprcTestUtil.PAYLOAD);
        given(req.getHeader("X-GitHub-Event")).willReturn("issue_comment");
        given(req.getReader()).willReturn(br);
        given(req.getCharacterEncoding()).willReturn("UTF-8");

        GhprcRootAction ra = new GhprcRootAction();
        ra.doIndex(req, null);
        GhprcTestUtil.waitForBuildsToFinish(project);
        
        assertThat(project.getBuilds().toArray().length).isEqualTo(1);
    }

    @Test
    public void testJson() throws Exception {
        given(req.getContentType()).willReturn("application/json");
        given(req.getHeader("X-GitHub-Event")).willReturn("issue_comment");

        // convert String into InputStream
        InputStream is = new ByteArrayInputStream(GhprcTestUtil.PAYLOAD.getBytes());
        // read it with BufferedReader
        br = spy(new BufferedReader(new InputStreamReader(is)));

        given(req.getReader()).willReturn(br);

        GhprcRootAction ra = new GhprcRootAction();
        ra.doIndex(req, null);

        verify(br, times(1)).close();
    }

}
