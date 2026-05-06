package com.booking.notification.repository;

import com.booking.notification.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserId(Long userId);

    List<Notification> findByStatusAndNextRetryAtBefore(String status, LocalDateTime now);

    List<Notification> findByStatus(String status);
}
