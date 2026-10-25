package com.walletledger.service;

import com.walletledger.dto.CreateUserRequest;
import com.walletledger.dto.UserResponse;
import com.walletledger.entity.User;
import com.walletledger.exception.ApiException;
import com.walletledger.exception.ErrorCode;
import com.walletledger.repository.UserRepository;

import jakarta.persistence.EntityManager;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            EntityManager entityManager,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String name = request.name().trim();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String password = request.password();

        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(
                    ErrorCode.BAD_REQUEST,
                    "Password must not exceed 72 UTF-8 bytes."
            );
        }

        if (userRepository.existsByEmail(email)) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "An account with this email already exists."
            );
        }

        String passwordHash = passwordEncoder.encode(password);
        User user = new User(
                UUID.randomUUID(),
                name,
                email,
                passwordHash
        );

        User savedUser = userRepository.saveAndFlush(user);
        entityManager.refresh(savedUser);

        return new UserResponse(
                savedUser.getId(),
                savedUser.getName(),
                savedUser.getEmail(),
                savedUser.getCreatedAt()
        );
    }
}