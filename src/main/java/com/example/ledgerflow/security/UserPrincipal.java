package com.example.ledgerflow.security;

import com.example.ledgerflow.domain.enums.Role;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authenticated caller as the service layer sees it. Rebuilt from the verified JWT.
 */
public record UserPrincipal(UUID id, String email, Set<Role> roles) {

    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_ROLES = "roles";

    public boolean isStaff() {
        return roles.contains(Role.ROLE_TELLER) || roles.contains(Role.ROLE_ADMIN);
    }

    public static UserPrincipal from(Jwt jwt) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        List<String> claim = jwt.getClaimAsStringList(CLAIM_ROLES);
        if (claim != null) {
            for (String name : claim) {
                try {
                    roles.add(Role.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                    // unknown role names grant nothing
                }
            }
        }
        return new UserPrincipal(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString(CLAIM_EMAIL), roles);
    }
}
