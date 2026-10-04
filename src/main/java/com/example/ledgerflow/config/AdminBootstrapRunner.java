package com.example.ledgerflow.config;

import com.example.ledgerflow.domain.entity.User;
import com.example.ledgerflow.domain.enums.Role;
import com.example.ledgerflow.repository.UserRepository;
import com.example.ledgerflow.util.Emails;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Creates the first administrator from {@code app.bootstrap.admin.*} when enabled.
 * No credential is stored in a migration, and an existing account is never modified.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.bootstrap.admin", name = "enabled", havingValue = "true")
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final int MIN_PASSWORD_LENGTH = 12;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Admin admin = properties.bootstrap().admin();

        if (!StringUtils.hasText(admin.email()) || !StringUtils.hasText(admin.password())) {
            log.warn("Admin bootstrap is enabled but email or password is not set; skipping");
            return;
        }
        if (admin.password().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "Bootstrap admin password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }

        String email = Emails.normalize(admin.email());
        if (userRepository.existsByEmail(email)) {
            log.info("Bootstrap admin {} already exists; nothing to do", email);
            return;
        }

        Set<Role> roles = new HashSet<>(Set.of(Role.ROLE_ADMIN));
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(admin.password()))
                .fullName(StringUtils.hasText(admin.fullName()) ? admin.fullName() : "System Administrator")
                .roles(roles)
                .build();
        try {
            userRepository.save(user);
            log.info("Created bootstrap admin {}", email);
        } catch (DataIntegrityViolationException e) {
            log.info("Bootstrap admin {} was created concurrently by another instance", email);
        }
    }
}
