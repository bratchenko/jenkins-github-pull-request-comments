package org.jenkinsci.plugins.ghprc;

import static com.google.common.collect.Lists.newArrayList;
import static org.kohsuke.github.GHIssueState.OPEN;
import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import hudson.model.AbstractProject;

import org.joda.time.DateTime;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRateLimit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.mockito.Mock;
import org.mockito.Mockito;

/**
 * @author mdelapenya (Manuel de la Peña)
 */
public abstract class GhprcITBaseTestCase {

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
    @Mock
    protected GitHub gitHub;

    // Stubs
    protected GHRateLimit ghRateLimit = new GHRateLimit();

    protected void beforeTest() throws Exception {
        given(ghprcGitHub.get()).willReturn(gitHub);
        given(gitHub.getRateLimit()).willReturn(ghRateLimit);
        given(gitHub.getRepository(anyString())).willReturn(ghRepository);
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

    protected void setRepositoryHelper(Ghprc ghprc) {
        ghprc.getRepository().setHelper(ghprc);
    }

    protected void setTriggerHelper(GhprcTrigger trigger, Ghprc ghprc) {
        trigger.setHelper(ghprc);
    }

    protected Ghprc spyCreatingGhprc(GhprcTrigger trigger, AbstractProject<?, ?> project) {

        return Mockito.spy(trigger.createGhprc(project));
    }


}