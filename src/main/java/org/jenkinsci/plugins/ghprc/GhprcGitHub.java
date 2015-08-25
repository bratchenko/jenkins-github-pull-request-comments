package org.jenkinsci.plugins.ghprc;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;

/**
 * @author janinko
 */
public class GhprcGitHub {
    private static final Logger logger = Logger.getLogger(GhprcGitHub.class.getName());
    private final GhprcTrigger trigger;
    
    public GhprcGitHub(GhprcTrigger trigger) {
        this.trigger = trigger;
    }

    public GitHub get() throws IOException {
        return trigger.getGitHub();
    }
    
    public boolean isUserMemberOfOrganization(String organisation, GHUser member) {
        boolean orgHasMember = false;
        try {
            GHOrganization org = get().getOrganization(organisation);
            orgHasMember = org.hasMember(member);
            logger.log(Level.INFO, "org.hasMember(member)? user:{0} org: {1} == {2}",
                    new Object[] { member.getLogin(), organisation, orgHasMember ? "yes" : "no" });

        } catch (IOException ex) {
            logger.log(Level.SEVERE, null, ex);
            return false;
        }
        return orgHasMember;
    }

    public String getBotUserLogin() {
        try {
            return get().getMyself().getLogin();
        } catch (IOException ex) {
            logger.log(Level.SEVERE, null, ex);
            return null;
        }
    }
}
