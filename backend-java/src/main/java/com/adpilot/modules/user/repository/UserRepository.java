package com.adpilot.modules.user.repository;

import com.adpilot.modules.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Find user by email address.
     */
    Optional<User> findByEmail(String email);

    /**
     * Find all users belonging to an organization.
     */
    List<User> findByOrgId(UUID orgId);

    /**
     * Check if a user exists with the given email.
     */
    boolean existsByEmail(String email);

    /**
     * Check if a user exists with the given email, comparing case-insensitively (Req 5.4).
     */
    boolean existsByEmailIgnoreCase(String email);
}
