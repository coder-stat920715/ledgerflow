package com.example.ledgerflow.service;

import com.example.ledgerflow.domain.entity.User;
import com.example.ledgerflow.domain.enums.Role;
import com.example.ledgerflow.dto.request.LoginRequest;
import com.example.ledgerflow.dto.request.RegisterRequest;
import com.example.ledgerflow.dto.response.TokenResponse;
import com.example.ledgerflow.dto.response.UserResponse;
import com.example.ledgerflow.exception.BusinessException;
import com.example.ledgerflow.mapper.UserMapper;
import com.example.ledgerflow.repository.UserRepository;
import com.example.ledgerflow.security.AppUserDetails;
import com.example.ledgerflow.security.TokenService;
import com.example.ledgerflow.util.Emails;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String BEARER = "Bearer";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final UserMapper userMapper;

    /**
     * Self-registration always yields a CUSTOMER. Staff roles are granted out of band.
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = Emails.normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw emailTaken();
        }

        Set<Role> roles = new HashSet<>(Set.of(Role.ROLE_CUSTOMER));
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .roles(roles)
                .build();
        try {
            return userMapper.toResponse(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // lost a race with a concurrent registration of the same email
            throw emailTaken();
        }
    }

    /**
     * Bad credentials and disabled accounts surface as AuthenticationException,
     * which the global handler maps to 401.
     */
    public TokenResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        Emails.normalize(request.email()), request.password()));
        AppUserDetails details = (AppUserDetails) authentication.getPrincipal();
        String token = tokenService.issueAccessToken(details.toPrincipal());
        return new TokenResponse(token, BEARER, tokenService.accessTokenTtl().toSeconds());
    }

    private static BusinessException emailTaken() {
        return new BusinessException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED",
                "An account with this email already exists");
    }
}
