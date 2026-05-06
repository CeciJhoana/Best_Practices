package com.booking.booking.controller;

import com.booking.booking.model.Booking;
import com.booking.booking.repository.BookingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/bookings")
public class BookingController {

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RestTemplate restTemplate;

    private final Object bookingLock = new Object();

    @PostMapping
    public synchronized ResponseEntity<?> createBooking(@RequestBody Map<String, Object> data) {
        Long userId = Long.valueOf(data.get("userId").toString());
        Long roomId = Long.valueOf(data.get("roomId").toString());
        LocalDate checkIn = LocalDate.parse(data.get("checkIn").toString());
        LocalDate checkOut = LocalDate.parse(data.get("checkOut").toString());

        ResponseEntity<Map> userResp = restTemplate.getForEntity(
                "http://user-service:5001/users/" + userId, Map.class);
        if (!userResp.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }

        Map<String, String> params = new HashMap<>();
        params.put("checkIn", checkIn.toString());
        params.put("checkOut", checkOut.toString());

        ResponseEntity<Map> invResp = restTemplate.getForEntity(
                "http://inventory-service:5003/rooms/" + roomId + "/availability?checkIn=" + checkIn + "&checkOut=" + checkOut,
                Map.class);
        if (!invResp.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(409).body(Map.of("error", "Room not available"));
        }

        synchronized (bookingLock) {
            Optional<Booking> existing = bookingRepository.findOverlapping(roomId, checkIn, checkOut);
            if (existing.isPresent()) {
                return ResponseEntity.status(409).body(Map.of("error", "Room already booked"));
            }

            Booking booking = new Booking();
            booking.setUserId(userId);
            booking.setRoomId(roomId);
            booking.setCheckIn(checkIn);
            booking.setCheckOut(checkOut);
            booking.setStatus("confirmed");
            Booking saved = bookingRepository.save(booking);

            Map<String, Object> notification = new HashMap<>();
            notification.put("userId", userId);
            notification.put("bookingId", saved.getId());
            notification.put("type", "booking_confirmed");
            notification.put("message", "Your booking #" + saved.getId() + " is confirmed!");
            restTemplate.postForEntity("http://notification-service:5004/notify", notification, Map.class);

            return ResponseEntity.ok(saved);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Booking> getBooking(@PathVariable Long id) {
        return bookingRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<Map<String, Object>> listBookings(@RequestParam(required = false) Long userId) {
        List<Booking> bookings = (userId != null)
                ? bookingRepository.findByUserId(userId)
                : bookingRepository.findAll();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Booking b : bookings) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", b.getId());
            entry.put("checkIn", b.getCheckIn());
            entry.put("checkOut", b.getCheckOut());
            entry.put("status", b.getStatus());

            try {
                Map user = restTemplate.getForObject(
                        "http://user-service:5001/users/" + b.getUserId(), Map.class);
                entry.put("user", user);

                Map room = restTemplate.getForObject(
                        "http://inventory-service:5003/rooms/" + b.getRoomId(), Map.class);
                entry.put("room", room);
            } catch (Exception ignored) {}

            result.add(entry);
        }
        return result;
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelBooking(@PathVariable Long id) {
        return bookingRepository.findById(id).map(b -> {
            b.setStatus("cancelled");
            bookingRepository.save(b);

            Map<String, Object> notification = new HashMap<>();
            notification.put("userId", b.getUserId());
            notification.put("bookingId", id);
            notification.put("type", "booking_cancelled");
            restTemplate.postForEntity("http://notification-service:5004/notify", notification, Map.class);

            return ResponseEntity.ok(Map.of("status", "cancelled"));
        }).orElse(ResponseEntity.notFound().build());
    }
}
