package org.jenkinsci.plugins.ghprc;

import com.google.common.base.Optional;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

/**
 * @author janinko
 */
@Extension
public class GhprcBuildListener extends RunListener<AbstractBuild<?, ?>> {

    @Override
    public void onStarted(AbstractBuild<?, ?> build, TaskListener listener) {
        final Optional<GhprcTrigger> trigger = findTrigger(build);
        if (trigger.isPresent()) {
            trigger.get().getBuilds().onStarted(build, listener);
        }
    }

    @Override
    public void onCompleted(AbstractBuild<?, ?> build, TaskListener listener) {
        final Optional<GhprcTrigger> trigger = findTrigger(build);
        if (trigger.isPresent()) {
            trigger.get().getBuilds().onCompleted(build, listener);
        }
    }

    private static Optional<GhprcTrigger> findTrigger(AbstractBuild<?, ?> build) {
        return Optional.fromNullable(Ghprc.extractTrigger(build));
    }
}
