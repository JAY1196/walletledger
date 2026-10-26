package com.walletledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "email", nullable = false, length = 254, unique = true)
    private String email;

    @Column(name = "password_hash", length = 60)
    private String passwordHash;

    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Instant createdAt;

    protected User() {
        // Required by JPA.
    }

    public User(UUID id, String name, String email, String passwordHash) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    /**
     * Compatibility constructor for internal/passwordless records.
     * New registrations must use the constructor that accepts a hash.
     */
    public User(UUID id, String name, String email) {
        this(id, name, email, null);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}