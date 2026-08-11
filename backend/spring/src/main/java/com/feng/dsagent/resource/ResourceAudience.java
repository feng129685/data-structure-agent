package com.feng.dsagent.resource;

import com.feng.dsagent.security.AuthenticatedUser;

record ResourceAudience(boolean allowClassroomOnly, boolean allowTeamOnly) {

    static ResourceAudience from(AuthenticatedUser user) {
        if (user == null) {
            return new ResourceAudience(false, false);
        }
        boolean teamMember = user.hasRole("TEACHER") || user.hasRole("ADMIN");
        return new ResourceAudience(true, teamMember);
    }
}
