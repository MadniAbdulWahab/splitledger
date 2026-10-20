package dev.splitledger.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByTokenHash(String tokenHash);
}
