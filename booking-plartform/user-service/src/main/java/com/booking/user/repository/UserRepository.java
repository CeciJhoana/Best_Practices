package com.booking.user.repository;

import com.booking.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    @Query("SELECT u FROM User u " +
            "WHERE LOWER(u.name) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))")
    List<User> searchByQuery(String q);
}
