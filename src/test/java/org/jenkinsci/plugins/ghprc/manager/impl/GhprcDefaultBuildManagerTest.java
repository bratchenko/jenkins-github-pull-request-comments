package org.jenkinsci.plugins.ghprc.manager.impl;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;

import java.util.HashMap;
import java.util.Map;

import com.coravy.hudson.plugins.github.GithubProjectProperty;

import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;

import org.jenkinsci.plugins.ghprc.Ghprc;
import org.jenkinsci.plugins.ghprc.GhprcITBaseTestCase;
import org.jenkinsci.plugins.ghprc.GhprcTestUtil;
import org.jenkinsci.plugins.ghprc.GhprcTrigger;
import org.jenkinsci.plugins.ghprc.manager.GhprcBuildManager;
import org.jenkinsci.plugins.ghprc.manager.factory.GhprcBuildManagerFactoryUtil;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.runners.MockitoJUnitRunner;

/**
 * @author mdelapenya (Manuel de la Peña)
 */
@RunWith(MockitoJUnitRunner.class)
public class GhprcDefaultBuildManagerTest extends GhprcITBaseTestCase {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Before
    public void setUp() throws Exception {
        // GhprcTestUtil.mockGithubUserPage();
        super.beforeTest();
    }

    @Test
    public void shouldCalculateUrlFromDefault() throws Exception {
        // GIVEN
        MatrixProject project = givenThatGhprcHasBeenTriggeredForAMatrixProject();

        // THEN
        assertThat(project.getBuilds().toArray().length).isEqualTo(1);

        MatrixBuild matrixBuild = project.getBuilds().getFirstBuild();

        GhprcBuildManager buildManager = GhprcBuildManagerFactoryUtil.getBuildManager(matrixBuild);

        assertThat(buildManager).isInstanceOf(GhprcDefaultBuildManager.class);

        assertThat(buildManager.calculateBuildUrl("defaultPublishedURL")).isEqualTo("defaultPublishedURL/" + matrixBuild.getUrl());
    }

    private MatrixProject givenThatGhprcHasBeenTriggeredForAMatrixProject() throws Exception {
        MatrixProject project = jenkinsRule.createMatrixProject("MTXPRJ");

        GhprcTrigger trigger = GhprcTestUtil.getTrigger(null);

        given(commitPointer.getSha()).willReturn("sha");
        
        Map<String, Object> config = new HashMap<String, Object>(1);
        config.put("publishedURL", "defaultPublishedURL");

        GhprcTestUtil.setupGhprcTriggerDescriptor(config);


        project.addProperty(new GithubProjectProperty("https://github.com/user/dropwizard"));

        given(ghPullRequest.getNumber()).willReturn(1);

        // Creating spy on ghprc, configuring repo
        Ghprc ghprc = spyCreatingGhprc(trigger, project);

        doReturn(ghprcGitHub).when(ghprc).getGitHub();

        setRepositoryHelper(ghprc);

        given(ghRepository.getPullRequest(1)).willReturn(ghPullRequest);

        // Configuring and adding Ghprc trigger
        project.addTrigger(trigger);

        // Configuring Git SCM
        project.setScm(GhprcTestUtil.provideGitSCM());

        trigger.start(project, true);

        setTriggerHelper(trigger, ghprc);

        GhprcTestUtil.triggerRunAndWait(10, trigger, project);

        return project;
    }
}