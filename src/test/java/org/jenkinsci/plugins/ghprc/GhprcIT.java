package org.jenkinsci.plugins.ghprc;

import com.coravy.hudson.plugins.github.GithubProjectProperty;
import com.google.common.collect.Lists;

import hudson.model.FreeStyleProject;
import hudson.plugins.git.GitSCM;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.stapler.RequestImpl;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import static com.google.common.collect.Lists.newArrayList;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Matchers.any;

@RunWith(MockitoJUnitRunner.class)
public class GhprcIT extends GhprcITBaseTestCase {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();
    
    @Mock
    private RequestImpl req;

    @Before
    public void setUp() throws Exception {
        // GhprcTestUtil.mockGithubUserPage();
        super.beforeTest();
    }

    @Test
    public void shouldBuildTriggersOnNewPR() throws Exception {
        // GIVEN
        FreeStyleProject project = jenkinsRule.createFreeStyleProject("PRJ");
        GhprcTrigger trigger = GhprcTestUtil.getTrigger(null);
        given(commitPointer.getSha()).willReturn("sha");
        GhprcTestUtil.setupGhprcTriggerDescriptor(null);
        project.addProperty(new GithubProjectProperty("https://github.com/user/dropwizard"));
        given(ghPullRequest.getNumber()).willReturn(1);

        // Creating spy on ghprc, configuring repo
        Ghprc ghprc = spy(trigger.createGhprc(project));
        doReturn(ghprcGitHub).when(ghprc).getGitHub();
        ghprc.getRepository().setHelper(ghprc);

        // Configuring and adding Ghprc trigger
        project.addTrigger(trigger);

        // Configuring Git SCM
        GitSCM scm = GhprcTestUtil.provideGitSCM();
        project.setScm(scm);

        trigger.start(project, true);
        trigger.setHelper(ghprc);

        GhprcTestUtil.triggerRunAndWait(10, trigger, project);

        assertThat(project.getBuilds().toArray().length).isEqualTo(1);
    }

    @Test
    public void shouldBuildTriggersOnUpdatingNewCommitsPR() throws Exception {
        // GIVEN
        FreeStyleProject project = jenkinsRule.createFreeStyleProject("PRJ");
        GhprcTrigger trigger = GhprcTestUtil.getTrigger(null);
        given(commitPointer.getSha()).willReturn("sha").willReturn("sha").willReturn("newOne").willReturn("newOne");
        given(ghPullRequest.getComments()).willReturn(Lists.<GHIssueComment> newArrayList());
        GhprcTestUtil.setupGhprcTriggerDescriptor(null);
        project.addProperty(new GithubProjectProperty("https://github.com/user/dropwizard"));
        given(ghPullRequest.getNumber()).willReturn(2).willReturn(2).willReturn(3).willReturn(3);
        Ghprc ghprc = spy(trigger.createGhprc(project));
        doReturn(ghprcGitHub).when(ghprc).getGitHub();
        trigger.start(project, true);
        trigger.setHelper(ghprc);
        ghprc.getRepository().setHelper(ghprc);
        project.addTrigger(trigger);
        GitSCM scm = GhprcTestUtil.provideGitSCM();
        project.setScm(scm);

        GhprcTestUtil.triggerRunAndWait(10, trigger, project);

        assertThat(project.getBuilds().toArray().length).isEqualTo(2);
    }

    @Test
    public void shouldBuildTriggersOnUpdatingRetestMessagePR() throws Exception {
        // GIVEN
        FreeStyleProject project = jenkinsRule.createFreeStyleProject("PRJ");
        GhprcTrigger trigger = GhprcTestUtil.getTrigger(null);

        given(commitPointer.getSha()).willReturn("sha");

        GHIssueComment comment = mock(GHIssueComment.class);
        given(comment.getBody()).willReturn("retest this please");
        given(comment.getUpdatedAt()).willReturn(new DateTime().plusDays(1).toDate());
        given(comment.getUser()).willReturn(ghUser);
        given(ghPullRequest.getComments()).willReturn(newArrayList(comment));
        given(ghPullRequest.getNumber()).willReturn(5).willReturn(5);
        GhprcTestUtil.setupGhprcTriggerDescriptor(null);
        project.addProperty(new GithubProjectProperty("https://github.com/user/dropwizard"));

        Ghprc ghprc = spy(trigger.createGhprc(project));
        doReturn(ghprcGitHub).when(ghprc).getGitHub();
        trigger.start(project, true);
        trigger.setHelper(ghprc);
        ghprc.getRepository().setHelper(ghprc);
        project.addTrigger(trigger);
        GitSCM scm = GhprcTestUtil.provideGitSCM();
        project.setScm(scm);

        GhprcTestUtil.triggerRunAndWait(10, trigger, project);
        assertThat(project.getBuilds().toArray().length).isEqualTo(2);
    }
    

    @Test
    public void shouldNotBuildDisabledBuild() throws Exception {
        // GIVEN
        FreeStyleProject project = jenkinsRule.createFreeStyleProject("PRJ");
        GhprcTrigger trigger = GhprcTestUtil.getTrigger(null);

        given(commitPointer.getSha()).willReturn("sha");

        GHIssueComment comment = mock(GHIssueComment.class);
        given(comment.getBody()).willReturn("retest this please");
        given(comment.getUpdatedAt()).willReturn(new DateTime().plusDays(1).toDate());
        given(comment.getUser()).willReturn(ghUser);
        given(ghPullRequest.getComments()).willReturn(newArrayList(comment));
        given(ghPullRequest.getNumber()).willReturn(5);
        GhprcTestUtil.setupGhprcTriggerDescriptor(null);
        project.addProperty(new GithubProjectProperty("https://github.com/user/dropwizard"));

        Ghprc ghprc = spy(trigger.createGhprc(project));
        doReturn(ghprcGitHub).when(ghprc).getGitHub();
        trigger.start(project, true);
        trigger.setHelper(ghprc);
        ghprc.getRepository().setHelper(ghprc);
        project.addTrigger(trigger);
        GitSCM scm = GhprcTestUtil.provideGitSCM();
        project.setScm(scm);
        
        project.disable();

        GhprcTestUtil.triggerRunAndWait(10, trigger, project);
        assertThat(project.getBuilds().toArray().length).isEqualTo(0);
        
        verify(ghRepository, times(0)).createCommitStatus(any(String.class), any(GHCommitState.class), any(String.class), any(String.class));
    }

}
