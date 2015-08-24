package org.jenkinsci.plugins.ghprc.extensions.status;

import org.jenkinsci.plugins.ghprc.GhprcPullRequest;
import org.jenkinsci.plugins.ghprc.GhprcTrigger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHRepository;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.BDDMockito.given;

@RunWith(MockitoJUnitRunner.class)
public class GhprcSimpleStatusTest {

    @Mock
    private GHRepository ghRepository;
    @Mock
    private GhprcPullRequest ghprcPullRequest;
    @Mock
    private GhprcTrigger trigger;
    
    @Test
    public void testMergedMessage() throws Exception {
        String mergedMessage = "Build triggered. sha1 is merged.";
        given(ghprcPullRequest.getHead()).willReturn("sha");
        given(ghprcPullRequest.isMergeable()).willReturn(true);

        GhprcSimpleStatus status = spy(new GhprcSimpleStatus("default"));
        status.onBuildTriggered(trigger, ghprcPullRequest, ghRepository);
        
        verify(ghRepository).createCommitStatus(eq("sha"), eq(GHCommitState.PENDING), isNull(String.class), eq(mergedMessage), eq("default"));
        verifyNoMoreInteractions(ghRepository);

        verify(ghprcPullRequest).getHead();
        verify(ghprcPullRequest).isMergeable();
        verifyNoMoreInteractions(ghprcPullRequest);
    }
    
    @Test
    public void testMergeConflictMessage() throws Exception {
        String mergedMessage = "Build triggered. sha1 is original commit.";
        given(ghprcPullRequest.getHead()).willReturn("sha");
        given(ghprcPullRequest.isMergeable()).willReturn(false);

        GhprcSimpleStatus status = spy(new GhprcSimpleStatus("default"));
        status.onBuildTriggered(trigger, ghprcPullRequest, ghRepository);
        
        verify(ghRepository).createCommitStatus(eq("sha"), eq(GHCommitState.PENDING), isNull(String.class), eq(mergedMessage), eq("default"));
        verifyNoMoreInteractions(ghRepository);

        verify(ghprcPullRequest).getHead();
        verify(ghprcPullRequest).isMergeable();
        verifyNoMoreInteractions(ghprcPullRequest);
    }
    
    @Test
    public void testDoesNotSendEmptyContext() throws Exception {
        String mergedMessage = "Build triggered. sha1 is original commit.";
        given(ghprcPullRequest.getHead()).willReturn("sha");
        given(ghprcPullRequest.isMergeable()).willReturn(false);

        GhprcSimpleStatus status = spy(new GhprcSimpleStatus(""));
        status.onBuildTriggered(trigger, ghprcPullRequest, ghRepository);
        
        verify(ghRepository).createCommitStatus(eq("sha"), eq(GHCommitState.PENDING), isNull(String.class), eq(mergedMessage), isNull(String.class));
        verifyNoMoreInteractions(ghRepository);

        verify(ghprcPullRequest).getHead();
        verify(ghprcPullRequest).isMergeable();
        verifyNoMoreInteractions(ghprcPullRequest);
    }
}
