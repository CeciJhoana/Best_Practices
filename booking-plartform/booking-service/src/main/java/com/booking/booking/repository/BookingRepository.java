package com.booking.booking.repository;

import com.booking.booking.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByUserId(Long userId);

    @Query("SELECT b FROM Booking b WHERE b.roomId = :roomId AND b.status = 'confirmed' " +
            "AND b.checkIn < :checkOut AND b.checkOut > :checkIn")
    Optional<Booking> findOverlapping(Long roomId, LocalDate checkIn, LocalDate checkOut);

    boolean existsByIdempotencyKey(String idempotencyKey);
}
