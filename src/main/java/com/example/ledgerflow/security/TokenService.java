package com.example.ledgerflow.security;

import com.example.ledgerflow.config.AppProperties;
import com.example.ledgerflow.domain.enums.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final AppProperties.Jwt jwtProperties;

    public TokenService(JwtEncoder jwtEncoder, AppProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = properties.security().jwt();
    }

    public String issueAccessToken(UserPrincipal principal) {
        Instant now = Instant.now();
        List<String> roles = principal.roles().stream().map(Role::name).sorted().toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.accessTokenTtl()))
                .subject(principal.id().toString())
                .id(UUID.randomUUID().toString())
                .claim(UserPrincipal.CLAIM_EMAIL, principal.email())
                .claim(UserPrincipal.CLAIM_ROLES, roles)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public Duration accessTokenTtl() {
        return jwtProperties.accessTokenTtl();
    }
}
