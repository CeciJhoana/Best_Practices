package com.booking.notification.controller;

import com.booking.notification.model.Notification;
import com.booking.notification.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@RestController
public class NotificationController {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private RestTemplate restTemplate;

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

        Map user = restTemplate.getForObject("http://user-service:5001/users/" + userId, Map.class);
        String email = user != null ? (String) user.get("email") : null;

        boolean sent = sendEmail(email, type, message);
        n.setStatus(sent ? "sent" : "failed");
        n.setAttempts(1);

        if (!sent) {
            for (int i = 0; i < 5; i++) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {}

                if (sendEmail(email, type, message)) {
                    n.setStatus("sent");
                    n.setAttempts(n.getAttempts() + 1);
                    break;
                }
                n.setAttempts(n.getAttempts() + 1);
            }
        }

        notificationRepository.save(n);

        return ResponseEntity.ok(Map.of("status", n.getStatus(), "id", n.getId()));
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

        List users = restTemplate.getForObject("http://user-service:5001/users", List.class);
        int count = 0;
        for (Object u : users) {
            Map userMap = (Map) u;
            Long userId = Long.valueOf(userMap.get("id").toString());

            Notification n = new Notification();
            n.setUserId(userId);
            n.setType("broadcast");
            n.setMessage(message);
            n.setStatus("pending");

            sendEmail((String) userMap.get("email"), "broadcast", message);
            n.setStatus("sent");
            notificationRepository.save(n);
            count++;
        }
        return ResponseEntity.ok(Map.of("sent", count));
    }
}
