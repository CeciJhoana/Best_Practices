package com.booking.user.controller;

import com.booking.user.model.User;
import com.booking.user.model.UserPreference;
import com.booking.user.repository.UserPreferenceRepository;
import com.booking.user.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPreferenceRepository preferenceRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping("/{id}")
    public ResponseEntity<User> getUser(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<Map<String, Object>> listUsers() {
        String sql = "SELECT u.id, u.name, u.email, COALESCE(COUNT(b.id), 0) AS booking_count " +
                "FROM users u LEFT JOIN bookings b ON b.user_id = u.id GROUP BY u.id";

        return jdbcTemplate.queryForList(sql);
    }

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody Map<String, String> data) throws Exception {
        User user = new User();
        user.setName(data.get("name"));
        user.setEmail(data.get("email"));

        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] hashBytes = md.digest(data.get("password").getBytes());
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) sb.append(String.format("%02x", b));
        user.setPasswordHash(sb.toString());

        return ResponseEntity.ok(userRepository.save(user));
    }

    @GetMapping("/{id}/profile")
    public Map<String, Object> getUserProfile(@PathVariable Long id) throws Exception {
        int timeMinutes = 10;
        String cacheKey = "user_profile_" + id;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return objectMapper.readValue(cached, new TypeReference<>() {
            });
        }

        Map<String, Object> profile = new HashMap<>();
        User user = userRepository.findById(id).orElse(null);
        profile.put("user", user);

        List<UserPreference> prefs = preferenceRepository.findByUserId(id);
        profile.put("preferences", prefs);

        List<Map<String, Object>> bookings = jdbcTemplate.queryForList(
                "SELECT * FROM bookings WHERE user_id = ? ORDER BY created_at DESC", id);
        profile.put("bookings", bookings);

        List<Map<String, Object>> reviews = jdbcTemplate.queryForList(
                "SELECT * FROM reviews WHERE user_id = ?", id);
        profile.put("reviews", reviews);

        List<Map<String, Object>> loyalty = jdbcTemplate.queryForList(
                "SELECT * FROM loyalty_points WHERE user_id = ?", id);
        profile.put("loyalty", loyalty);

        String profileJson = objectMapper.writeValueAsString(profile);
        redisTemplate.opsForValue().set(cacheKey, profileJson, Duration.ofMinutes(timeMinutes));

        return profile;
    }

    @GetMapping("/search")
    public List<User> searchUsers(@RequestParam String q) {
        return userRepository.searchByQuery(q);
    }
}
