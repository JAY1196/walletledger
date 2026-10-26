package com.walletledger.service;

import com.walletledger.dto.LoginRequest;
import com.walletledger.dto.LoginResponse;
import com.walletledger.entity.User;
import com.walletledger.exception.ApiException;
import com.walletledger.exception.ErrorCode;
import com.walletledger.repository.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String password = request.password();

        User user = userRepository.findByEmail(email)
                .orElseThrow(AuthService::invalidCredentials);

        String passwordHash = user.getPasswordHash();

        if (passwordHash == null
                || passwordHash.isBlank()
                || !passwordEncoder.matches(password, passwordHash)) {
            throw invalidCredentials();
        }

        String token = jwtService.generateToken(user.getId());

        return new LoginResponse(
                token,
                "Bearer",
                jwtService.getExpirationSeconds(),
                user.getId());
    }

    private static ApiException invalidCredentials() {
        return new ApiException(
                ErrorCode.UNAUTHENTICATED,
                "Invalid email or password.");
    }
}