package com.booking.notification.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "notifications")
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long bookingId;
    private String type;
    @Column(length = 2000)
    private String message;
    private String status;  
    private Integer attempts = 0;
    private Integer maxAttempts = 5;
    private LocalDateTime nextRetryAt;  
    private LocalDateTime createdAt = LocalDateTime.now();
}
