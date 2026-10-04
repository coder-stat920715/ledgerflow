package com.example.ledgerflow.security;

import com.example.ledgerflow.domain.entity.User;
import com.example.ledgerflow.repository.UserRepository;
import com.example.ledgerflow.util.Emails;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(Emails.normalize(username))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        return new AppUserDetails(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.isEnabled(),
                Set.copyOf(user.getRoles()));
    }
}
