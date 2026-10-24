package com.walletledger.service;

import com.walletledger.dto.CreateUserRequest;
import com.walletledger.dto.UserResponse;
import com.walletledger.entity.User;
import com.walletledger.exception.ApiException;
import com.walletledger.exception.ErrorCode;
import com.walletledger.repository.UserRepository;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final EntityManager entityManager;

    public UserService(
            UserRepository userRepository,
            EntityManager entityManager) {
        this.userRepository = userRepository;
        this.entityManager = entityManager;
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String name = request.name().trim();
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(email)) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "An account with this email already exists."
            );
        }

        User user = new User(UUID.randomUUID(), name, email);
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
