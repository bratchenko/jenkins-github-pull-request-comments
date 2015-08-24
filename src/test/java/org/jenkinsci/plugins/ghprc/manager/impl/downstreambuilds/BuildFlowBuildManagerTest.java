package org.jenkinsci.plugins.ghprc.manager.impl.downstreambuilds;

import static org.fest.assertions.Assertions.assertThat;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;

import com.cloudbees.plugins.flow.BuildFlow;
import com.cloudbees.plugins.flow.FlowRun;
import com.cloudbees.plugins.flow.JobInvocation;
import com.coravy.hudson.plugins.github.GithubProjectProperty;

import java.util.Iterator;

import org.jenkinsci.plugins.ghprc.Ghprc;
import org.jenkinsci.plugins.ghprc.GhprcITBaseTestCase;
import org.jenkinsci.plugins.ghprc.GhprcTestUtil;
import org.jenkinsci.plugins.ghprc.GhprcTrigger;
import org.jenkinsci.plugins.ghprc.manager.GhprcBuildManager;
import org.jenkinsci.plugins.ghprc.manager.factory.GhprcBuildManagerFactoryUtil;
import org.jenkinsci.plugins.ghprc.rules.JenkinsRuleWithBuildFlow;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.mockito.runners.MockitoJUnitRunner;

/**
 * @author mdelapenya (Manuel de la Peña)
 */
@RunWith(MockitoJUnitRunner.class)
public class BuildFlowBuildManagerTest extends GhprcITBaseTestCase {

    @Rule
    public JenkinsRuleWithBuildFlow jenkinsRule = new JenkinsRuleWithBuildFlow();

    @Before
    public void setUp() throws Exception {
        super.beforeTest();
    }

    @Test
    public void shouldCalculateUrlWithDownstreamBuilds() throws Exception {
        // GIVEN
        BuildFlow buildFlowProject = givenThatGhprcHasBeenTriggeredForABuildFlowProject();

        // THEN
        assertThat(buildFlowProject.getBuilds().toArray().length).isEqualTo(1);

        FlowRun flowRun = buildFlowProject.getBuilds().getFirstBuild();

        GhprcBuildManager buildManager = GhprcBuildManagerFactoryUtil.getBuildManager(flowRun);

        assertThat(buildManager).isInstanceOf(BuildFlowBuildManager.class);

        Iterator<?> iterator = buildManager.downstreamProjects();

        StringBuilder expectedUrl = new StringBuilder();

        int count = 0;

        while (iterator.hasNext()) {
            Object downstreamBuild = iterator.next();

            assertThat(downstreamBuild).isInstanceOf(JobInvocation.class);

            JobInvocation jobInvocation = (JobInvocation) downstreamBuild;

            String jobInvocationBuildUrl = jobInvocation.getBuildUrl();

            expectedUrl.append("\n<a href='");
            expectedUrl.append(jobInvocationBuildUrl);
            expectedUrl.append("'>");
            expectedUrl.append(jobInvocationBuildUrl);
            expectedUrl.append("</a>");

            count++;
        }

        assertThat(count).isEqualTo(4);

        assertThat(buildManager.calculateBuildUrl(null)).isEqualTo(expectedUrl.toString());
    }

    private BuildFlow givenThatGhprcHasBeenTriggeredForABuildFlowProject() throws Exception {

        BuildFlow buildFlowProject = jenkinsRule.createBuildFlowProject();

        jenkinsRule.createFreeStyleProject("downstreamProject1");
        jenkinsRule.createFreeStyleProject("downstreamProject2");
        jenkinsRule.createFreeStyleProject("downstreamProject3");

        StringBuilder dsl = new StringBuilder();

        dsl.append("parallel (");
        dsl.append("    { build(\"downstreamProject1\") },");
        dsl.append("    { build(\"downstreamProject2\") }");
        dsl.append(")");
        dsl.append("{ build(\"downstreamProject3\") }");

        buildFlowProject.setDsl(dsl.toString());

        GhprcTrigger trigger = GhprcTestUtil.getTrigger(null);

        given(commitPointer.getSha()).willReturn("sha");
        GhprcTestUtil.setupGhprcTriggerDescriptor(null);

        buildFlowProject.addProperty(new GithubProjectProperty("https://github.com/user/dropwizard"));

        given(ghPullRequest.getNumber()).willReturn(1);

        // Creating spy on ghprc, configuring repo
        Ghprc ghprc = spyCreatingGhprc(trigger, buildFlowProject);

        doReturn(ghprcGitHub).when(ghprc).getGitHub();

        setRepositoryHelper(ghprc);

        given(ghRepository.getPullRequest(1)).willReturn(ghPullRequest);

        // Configuring and adding Ghprc trigger
        buildFlowProject.addTrigger(trigger);

        // Configuring Git SCM
        buildFlowProject.setScm(GhprcTestUtil.provideGitSCM());

        trigger.start(buildFlowProject, true);

        setTriggerHelper(trigger, ghprc);

        GhprcTestUtil.triggerRunAndWait(10, trigger, buildFlowProject);

        return buildFlowProject;
    }

}
