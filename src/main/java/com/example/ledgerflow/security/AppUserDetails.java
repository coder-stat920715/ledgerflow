package com.example.ledgerflow.security;

import com.example.ledgerflow.domain.enums.Role;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Login-time view of a user, used only by the DaoAuthenticationProvider.
 * Request-time identity comes from the JWT (see {@link UserPrincipal}).
 */
public record AppUserDetails(UUID id, String email, String passwordHash, boolean enabled, Set<Role> roles)
        implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(role -> new SimpleGrantedAuthority(role.name())).toList();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public UserPrincipal toPrincipal() {
        return new UserPrincipal(id, email, roles);
    }

    @Override
    public String toString() {
        return "AppUserDetails[id=" + id + ", email=" + email + ", enabled=" + enabled + "]";
    }
}
