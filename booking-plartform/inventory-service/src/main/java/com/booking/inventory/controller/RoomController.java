package com.booking.inventory.controller;

import com.booking.inventory.model.Room;
import com.booking.inventory.repository.RoomRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/rooms")
public class RoomController {

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RestTemplate restTemplate;

    private BigDecimal computeDynamicPrice(Long roomId, String checkIn, String checkOut) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {}

        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) return BigDecimal.ZERO;

        Integer demand = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE room_id = ? AND check_in >= ?",
                Integer.class, roomId, checkIn);

        BigDecimal avgPrice = jdbcTemplate.queryForObject(
                "SELECT AVG(base_price) FROM rooms WHERE category = ?",
                BigDecimal.class, room.getCategory());

        double multiplier = 1.0 + (demand * 0.05);
        return room.getBasePrice().multiply(BigDecimal.valueOf(multiplier));
    }

    @GetMapping
    public List<Map<String, Object>> listRooms() {
        List<Room> rooms = roomRepository.findByIsActiveTrue();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Room room : rooms) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", room.getId());
            entry.put("name", room.getName());
            entry.put("category", room.getCategory());
            entry.put("capacity", room.getCapacity());
            entry.put("dynamicPrice", computeDynamicPrice(room.getId(), "2024-01-01", "2024-01-02"));
            result.add(entry);
        }
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getRoom(@PathVariable Long id) {
        String cacheKey = "room_" + id;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return ResponseEntity.ok(Map.of("cached", cached));
        }

        Optional<Room> room = roomRepository.findById(id);
        if (room.isEmpty()) return ResponseEntity.notFound().build();

        redisTemplate.opsForValue().set(cacheKey, room.get().toString());

        return ResponseEntity.ok(room.get());
    }

    @GetMapping("/{id}/availability")
    public ResponseEntity<?> checkAvailability(
            @PathVariable Long id,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        List<Map<String, Object>> conflicts = jdbcTemplate.queryForList(
                "SELECT * FROM bookings WHERE room_id = ? AND status = 'confirmed' " +
                        "AND check_in < ? AND check_out > ?",
                id, checkOut, checkIn);

        if (!conflicts.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of("available", false));
        }
        return ResponseEntity.ok(Map.of("available", true));
    }

    @PostMapping
    public ResponseEntity<Room> createRoom(@RequestBody Room room) {
        room.setIsActive(true);
        return ResponseEntity.ok(roomRepository.save(room));
    }

    @GetMapping("/search")
    public List<Map<String, Object>> searchRooms(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") Integer minCapacity,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        List<Room> allRooms = roomRepository.findByIsActiveTrue();
        List<Map<String, Object>> available = new ArrayList<>();

        for (Room room : allRooms) {
            if (category != null && !room.getCategory().equals(category)) continue;
            if (room.getCapacity() < minCapacity) continue;

            try {
                ResponseEntity<Map> resp = restTemplate.getForEntity(
                        "http://inventory-service:5003/rooms/" + room.getId() +
                                "/availability?checkIn=" + checkIn + "&checkOut=" + checkOut,
                        Map.class);
                if (resp.getStatusCode().is2xxSuccessful()) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("id", room.getId());
                    entry.put("name", room.getName());
                    entry.put("category", room.getCategory());
                    entry.put("basePrice", room.getBasePrice());
                    available.add(entry);
                }
            } catch (Exception ignored) {}
        }
        return available;
    }
}
