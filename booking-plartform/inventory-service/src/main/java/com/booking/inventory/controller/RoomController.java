package com.booking.inventory.controller;

import com.booking.inventory.model.Room;
import com.booking.inventory.repository.RoomRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
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

    @Autowired
    private ObjectMapper objectMapper;

    private BigDecimal computeDynamicPrice(Long roomId, String checkIn, String checkOut) {
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) {
            throw new NoSuchElementException("Room not found");
        }

        Integer demand = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE room_id = ? AND check_in >= ?",
                Integer.class, roomId, checkIn);
        if (demand == null) {
            demand = 0;
        }

        double multiplier = 1.0 + (demand * 0.05);
        return room.getBasePrice().multiply(BigDecimal.valueOf(multiplier));
    }

    private String dynamicPriceCacheKey(Long roomId, String checkIn, String checkOut) {
        return String.format("room_dynamic_price_%d_%s_%s", roomId, checkIn, checkOut);
    }

    private BigDecimal getCachedDynamicPrice(Long roomId, String checkIn, String checkOut) {
        String cacheKey = dynamicPriceCacheKey(roomId, checkIn, checkOut);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached == null) {
            return null;
        }
        return new BigDecimal(cached);
    }

    private void cacheDynamicPrice(Long roomId, String checkIn, String checkOut, BigDecimal dynamicPrice) {
        String cacheKey = dynamicPriceCacheKey(roomId, checkIn, checkOut);
        redisTemplate.opsForValue().set(cacheKey, dynamicPrice.toString(), Duration.ofMinutes(5));
    }

    private boolean isRoomAvailable(Long id, String checkIn, String checkOut) {
        List<Map<String, Object>> conflicts = jdbcTemplate.queryForList(
                "SELECT 1 FROM bookings WHERE room_id = ? AND status = 'confirmed' " +
                        "AND check_in < ? AND check_out > ?",
                id, checkOut, checkIn);
        return conflicts.isEmpty();
    }

    @GetMapping
    public List<Map<String, Object>> listRooms() {
        List<Room> rooms = roomRepository.findByIsActiveTrue();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Room room : rooms) {
            BigDecimal dynamicPrice = getCachedDynamicPrice(room.getId(), "2024-01-01", "2024-01-02");
            if (dynamicPrice == null) {
                dynamicPrice = computeDynamicPrice(room.getId(), "2024-01-01", "2024-01-02");
                cacheDynamicPrice(room.getId(), "2024-01-01", "2024-01-02", dynamicPrice);
            }

            Map<String, Object> entry = new HashMap<>();
            entry.put("id", room.getId());
            entry.put("name", room.getName());
            entry.put("category", room.getCategory());
            entry.put("capacity", room.getCapacity());
            entry.put("dynamicPrice", dynamicPrice);
            result.add(entry);
        }
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getRoom(@PathVariable Long id) throws Exception {
        String cacheKey = "room_" + id;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            Map<String, Object> cachedRoom = objectMapper.readValue(cached, new TypeReference<>() {
            });
            return ResponseEntity.ok(cachedRoom);
        }

        Optional<Room> room = roomRepository.findById(id);
        if (room.isEmpty()) return ResponseEntity.notFound().build();

        String roomJson = objectMapper.writeValueAsString(room.get());
        redisTemplate.opsForValue().set(cacheKey, roomJson, Duration.ofMinutes(10));

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
            if (!isRoomAvailable(room.getId(), checkIn, checkOut)) continue;

            Map<String, Object> entry = new HashMap<>();
            entry.put("id", room.getId());
            entry.put("name", room.getName());
            entry.put("category", room.getCategory());
            entry.put("basePrice", room.getBasePrice());
            available.add(entry);
        }
        return available;
    }
}
