package com.booking.user.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "user_preferences")
public class UserPreference {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private String prefKey;
    private String prefValue;
}
