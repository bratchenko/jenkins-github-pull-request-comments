package org.jenkinsci.plugins.ghprc;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHUser;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.util.Date;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link GhprcPullRequest}.
 */
@RunWith(MockitoJUnitRunner.class)
public class GhprcPullRequestTest {

    @Mock
    private GHPullRequest pr;
    @Mock
    private Ghprc helper;
    @Mock
    private GhprcRepository repo;

    @Test
    public void testConstructorWhenAuthorIsWhitelisted() throws IOException {
        // GIVEN
        GHUser ghUser = mock(GHUser.class);
        GHCommitPointer head = mock(GHCommitPointer.class);
        GHCommitPointer base = mock(GHCommitPointer.class);
        given(head.getSha()).willReturn("some sha");
        given(base.getRef()).willReturn("some ref");

        // Mocks for GHPullRequest
        given(pr.getNumber()).willReturn(10);
        given(pr.getUpdatedAt()).willReturn(new Date());
        given(pr.getTitle()).willReturn("title");
        given(pr.getHead()).willReturn(head);
        given(pr.getBase()).willReturn(base);
        given(pr.getUser()).willReturn(ghUser);
        given(ghUser.getEmail()).willReturn("email");

        // Mocks for GhprcRepository
        given(repo.getName()).willReturn("repoName");

        // WHEN
        GhprcPullRequest ghprcPullRequest = new GhprcPullRequest(pr, helper, repo);

        // THEN
        assertThat(ghprcPullRequest.getId()).isEqualTo(10);
        assertThat(ghprcPullRequest.getAuthorEmail()).isEqualTo("email");
        assertThat(ghprcPullRequest.getHead()).isEqualTo("some sha");
        assertThat(ghprcPullRequest.getTitle()).isEqualTo("title");
        assertThat(ghprcPullRequest.getTarget()).isEqualTo("some ref");
        assertThat(ghprcPullRequest.isMergeable()).isFalse();
    }

    @Test
    public void testInitRepoNameNull() throws IOException {
        // GIVEN
        GHUser ghUser = mock(GHUser.class);
        GHCommitPointer head = mock(GHCommitPointer.class);
        GHCommitPointer base = mock(GHCommitPointer.class);

        // Mocks for GHPullRequest
        given(pr.getNumber()).willReturn(10);
        given(pr.getUpdatedAt()).willReturn(new Date());
        given(pr.getTitle()).willReturn("title");
        given(pr.getHead()).willReturn(head);
        given(pr.getBase()).willReturn(base);
        given(head.getSha()).willReturn("some sha");
        given(base.getRef()).willReturn("some ref");
        given(pr.getUser()).willReturn(ghUser);
        given(ghUser.getEmail()).willReturn("email");

        // Mocks for GhprcRepository
        given(repo.getName()).willReturn(null);
        doNothing().when(repo).addOrUpdateComment(eq(10), anyString(), anyString(), null, null);

        GhprcPullRequest ghprcPullRequest = new GhprcPullRequest(pr, helper, repo);
        GhprcRepository ghprcRepository = mock(GhprcRepository.class);
        given(ghprcRepository.getName()).willReturn("name");

        // WHEN
        ghprcPullRequest.init(helper, ghprcRepository);

        // THEN
        verify(ghprcRepository, times(1)).getName();

    }

    @Test
    public void testInitRepoNameNotNull() throws IOException {
        // GIVEN
        GHUser ghUser = mock(GHUser.class);
        GHCommitPointer head = mock(GHCommitPointer.class);
        GHCommitPointer base = mock(GHCommitPointer.class);

        // Mocks for GHPullRequest
        given(pr.getNumber()).willReturn(10);
        given(pr.getUpdatedAt()).willReturn(new Date());
        given(pr.getTitle()).willReturn("title");
        given(pr.getHead()).willReturn(head);
        given(pr.getBase()).willReturn(base);
        given(head.getSha()).willReturn("some sha");
        given(base.getRef()).willReturn("some ref");
        given(pr.getUser()).willReturn(ghUser);
        given(ghUser.getEmail()).willReturn("email");

        // Mocks for GhprcRepository
        given(repo.getName()).willReturn("name");
        doNothing().when(repo).addOrUpdateComment(eq(10), anyString(), anyString(), null, null);

        GhprcPullRequest ghprcPullRequest = new GhprcPullRequest(pr, helper, repo);
        GhprcRepository ghprcRepository = mock(GhprcRepository.class);
        given(ghprcRepository.getName()).willReturn("name");

        // WHEN
        ghprcPullRequest.init(helper, ghprcRepository);

        // THEN
        verify(ghprcRepository, never()).getName();
    }

}
