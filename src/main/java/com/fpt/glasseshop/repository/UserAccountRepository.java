package com.fpt.glasseshop.repository;

import com.fpt.glasseshop.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByEmail(String email);


    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmail(String email);

}
