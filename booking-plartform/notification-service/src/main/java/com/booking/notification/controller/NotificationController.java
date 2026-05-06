package com.booking.notification.controller;

import com.booking.notification.model.Notification;
import com.booking.notification.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

@RestController
public class NotificationController {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private RestTemplate restTemplate;

    private static final int MAX_RETRIES = 5;
    private static final int BASE_RETRY_DELAY_MS = 1000; 

    @PostMapping("/notify")
    public ResponseEntity<?> notify(@RequestBody Map<String, Object> data) {
        Long userId = Long.valueOf(data.get("userId").toString());
        Long bookingId = data.get("bookingId") != null ? Long.valueOf(data.get("bookingId").toString()) : null;
        String type = (String) data.get("type");
        String message = (String) data.getOrDefault("message", "");

        Notification n = new Notification();
        n.setUserId(userId);
        n.setBookingId(bookingId);
        n.setType(type);
        n.setMessage(message);
        n.setStatus("pending");
        n.setMaxAttempts(MAX_RETRIES);
        n.setNextRetryAt(LocalDateTime.now());
        Notification saved = notificationRepository.save(n);

        sendNotificationAsync(saved);

        return ResponseEntity.accepted().body(Map.of("status", "queued", "id", saved.getId()));
    }

    @Async
    public void sendNotificationAsync(Notification notification) {
        try {
            Map user = restTemplate.getForObject("http://user-service:5001/users/" + notification.getUserId(), Map.class);
            String email = user != null ? (String) user.get("email") : null;

            if (email == null) {
                notification.setStatus("dead_letter");
                notificationRepository.save(notification);
                return;
            }

            if (sendEmail(email, notification.getType(), notification.getMessage())) {
                notification.setStatus("sent");
                notification.setAttempts(notification.getAttempts() + 1);
                notificationRepository.save(notification);
            } else {
                retryWithExponentialBackoff(notification);
            }
        } catch (Exception e) {
            retryWithExponentialBackoff(notification);
        }
    }

    private void retryWithExponentialBackoff(Notification notification) {
        notification.setAttempts(notification.getAttempts() + 1);

        if (notification.getAttempts() >= MAX_RETRIES) {
            notification.setStatus("dead_letter");
            notificationRepository.save(notification);
            return;
        }

        long delayMs = (long) (BASE_RETRY_DELAY_MS * Math.pow(2, notification.getAttempts()) +
                Math.random() * 1000);
        notification.setNextRetryAt(LocalDateTime.now().plusSeconds(delayMs / 1000));
        notification.setStatus("pending");
        notificationRepository.save(notification);
    }

    private boolean sendEmail(String email, String type, String message) {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                    "http://smtp-gateway:8080/send?to=" + email + "&type=" + type, String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    @GetMapping("/notifications")
    public List<Notification> listNotifications(@RequestParam(required = false) Long userId) {
        if (userId != null) {
            return notificationRepository.findByUserId(userId);
        }
        return notificationRepository.findAll();
    }

    @GetMapping("/notifications/{id}")
    public ResponseEntity<Notification> getNotification(@PathVariable Long id) {
        return notificationRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/notify/broadcast")
    public ResponseEntity<?> broadcast(@RequestBody Map<String, Object> data) {
        String message = (String) data.get("message");
        broadcastAsync(message);
        return ResponseEntity.accepted().body(Map.of("status", "broadcast_queued"));
    }

    @Async
    public void broadcastAsync(String message) {
        try {
            List users = restTemplate.getForObject("http://user-service:5001/users", List.class);
            if (users == null || users.isEmpty()) {
                return;
            }

            List<Notification> notifications = new ArrayList<>();
            for (Object u : users) {
                Map userMap = (Map) u;
                Long userId = Long.valueOf(userMap.get("id").toString());

                Notification n = new Notification();
                n.setUserId(userId);
                n.setType("broadcast");
                n.setMessage(message);
                n.setStatus("pending");
                n.setMaxAttempts(MAX_RETRIES);
                n.setNextRetryAt(LocalDateTime.now());
                notifications.add(n);
            }

            List<Notification> savedNotifications = notificationRepository.saveAll(notifications);

            for (Notification n : savedNotifications) {
                sendNotificationAsync(n);
            }
        } catch (Exception e) {
            System.err.println("Broadcast failed: " + e.getMessage());
        }
    }

    public void retryPendingNotifications() {
        LocalDateTime now = LocalDateTime.now();
        List<Notification> pending = notificationRepository.findByStatusAndNextRetryAtBefore("pending", now);

        for (Notification notification : pending) {
            sendNotificationAsync(notification);
        }
    }
}
