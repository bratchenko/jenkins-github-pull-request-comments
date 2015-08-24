package org.jenkinsci.plugins.ghprc;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Ray Sennewald & David Wang
 */

public class GhprcBranch extends AbstractDescribableImpl<GhprcBranch> {
    private String branch;

    public String getBranch() {
        return branch;
    }

    public boolean matches(String s) {
        return s.matches(branch);
    }

    @DataBoundConstructor
    public GhprcBranch(String branch) {
        this.branch = branch.trim();
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<GhprcBranch> {
        @Override
        public String getDisplayName() {
            return "Branch";
        }
    }
}
