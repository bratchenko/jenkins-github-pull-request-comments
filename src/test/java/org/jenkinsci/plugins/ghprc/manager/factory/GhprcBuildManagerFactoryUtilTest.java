package org.jenkinsci.plugins.ghprc.manager.factory;

import static org.fest.assertions.Assertions.assertThat;

import com.cloudbees.plugins.flow.BuildFlow;
import com.cloudbees.plugins.flow.FlowRun;

import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;

import org.jenkinsci.plugins.ghprc.manager.GhprcBuildManager;
import org.jenkinsci.plugins.ghprc.manager.impl.GhprcDefaultBuildManager;
import org.jenkinsci.plugins.ghprc.manager.impl.downstreambuilds.BuildFlowBuildManager;
import org.jenkinsci.plugins.ghprc.rules.JenkinsRuleWithBuildFlow;

import org.junit.Rule;
import org.junit.Test;

/**
 * @author mdelapenya (Manuel de la Peña)
 */
public class GhprcBuildManagerFactoryUtilTest {

    @Rule
    public JenkinsRuleWithBuildFlow jenkinsRule = new JenkinsRuleWithBuildFlow();

    @Test
    public void shouldReturnDefaultManager() throws Exception {
        // GIVEN
        MatrixProject project = jenkinsRule.createMatrixProject("PRJ");

        GhprcBuildManager buildManager = GhprcBuildManagerFactoryUtil.getBuildManager(new MatrixBuild(project));

        // THEN
        assertThat(buildManager).isInstanceOf(GhprcDefaultBuildManager.class);
    }

    @Test
    public void shouldReturnBuildFlowManager() throws Exception {
        // GIVEN
        BuildFlow buildFlowProject = jenkinsRule.createBuildFlowProject("BFPRJ");

        GhprcBuildManager buildManager = GhprcBuildManagerFactoryUtil.getBuildManager(new FlowRun(buildFlowProject));

        // THEN
        assertThat(buildManager).isInstanceOf(BuildFlowBuildManager.class);
    }

}