package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link BookingService}.
 * Covers createBooking, getBookingById, calculateRoomPrice,
 * isRoomAvailable, generateReport, and sha256Hash (indirectly).
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidInputs_returnsMapWithBookingId() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("bookingId"));
        assertTrue(((String) result.get("bookingId")).startsWith("BK-"));
    }

    @Test
    void createBooking_withValidInputs_returnsGuestName() {
        doNothing().when(jdbcTemplate).execute(anyString());

        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-10", "2024-07-15");

        assertEquals("Bob", result.get("guestName"));
    }

    @Test
    void createBooking_withValidInputs_returnsRoomType() {
        doNothing().when(jdbcTemplate).execute(anyString());

        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-08-01", "2024-08-03");

        assertEquals("STANDARD", result.get("roomType"));
    }

    @Test
    void createBooking_withValidInputs_returnsCheckInAndCheckOut() {
        doNothing().when(jdbcTemplate).execute(anyString());

        Map<String, Object> result = bookingService.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-10");

        assertEquals("2024-09-01", result.get("checkIn"));
        assertEquals("2024-09-10", result.get("checkOut"));
    }

    @Test
    void createBooking_withValidInputs_returnsConfirmationCode() {
        doNothing().when(jdbcTemplate).execute(anyString());

        Map<String, Object> result = bookingService.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-07");

        assertNotNull(result.get("confirmationCode"));
        // SHA-256 hex string is 64 characters long
        assertEquals(64, ((String) result.get("confirmationCode")).length());
    }

    @Test
    void createBooking_withValidInputs_returnsDbHost() {
        doNothing().when(jdbcTemplate).execute(anyString());

        Map<String, Object> result = bookingService.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-04");

        assertNotNull(result.get("dbHost"));
        assertEquals("db-prod.resorts-internal.com", result.get("dbHost"));
    }

    @Test
    void createBooking_executesJdbcInsert() {
        doNothing().when(jdbcTemplate).execute(anyString());

        bookingService.createBooking("Grace", "STANDARD", "2024-12-01", "2024-12-03");

        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    @Test
    void createBooking_withSpecialCharactersInName_doesNotThrow() {
        doNothing().when(jdbcTemplate).execute(anyString());

        assertDoesNotThrow(() ->
                bookingService.createBooking("O'Brien", "SUITE", "2024-01-01", "2024-01-05"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingById
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingById_whenFound_returnsBookingMap() {
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "Alice");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(dbRow);

        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("id"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("No rows found"));

        Map<String, Object> result = bookingService.getBookingById("BK-UNKNOWN");

        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(((String) result.get("error")).contains("BK-UNKNOWN"));
    }

    @Test
    void getBookingById_whenExceptionThrown_errorMessageContainsBookingId() {
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("EmptyResultDataAccessException"));

        Map<String, Object> result = bookingService.getBookingById("BK-XYZ999");

        assertTrue(((String) result.get("error")).contains("BK-XYZ999"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — room type variations
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_noLoyalty_1Night() {
        // 120.0 * 1 = 120.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoom_normalSeason_noLoyalty_1Night() {
        // 200.0 * 1 = 200.00
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoom_normalSeason_noLoyalty_1Night() {
        // 350.0 * 1 = 350.00
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoom_normalSeason_noLoyalty_1Night() {
        // 600.0 * 1 = 600.00
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_defaultsToStandardPrice() {
        // default -> 120.0 * 1 = 120.00
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — season multipliers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_peakSeason_appliesMultiplier() {
        // STANDARD 120 * 1.5 (PEAK) * 1 night = 180.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_offSeason_appliesMultiplier() {
        // STANDARD 120 * 0.8 (OFF) * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_normalSeason_noSeasonMultiplier() {
        // STANDARD 120 * 1 night = 120.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — loyalty discounts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_goldLoyalty_appliesDiscount() {
        // STANDARD 120 * 0.9 (GOLD) * 1 night = 108.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_appliesDiscount() {
        // STANDARD 120 * 0.8 (PLATINUM) * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_appliesDiscount() {
        // STANDARD 120 * 0.7 (DIAMOND) * 1 night = 84.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_noLoyalty_noDiscount() {
        // STANDARD 120 * 1 night = 120.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — night-stay discounts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_sevenNights_appliesWeeklyDiscount() {
        // STANDARD 120 * 0.95 (7+ nights) * 7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesBiweeklyDiscount() {
        // STANDARD 120 * 0.90 (14+ nights) * 14 = 1512.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_sixNights_noNightDiscount() {
        // STANDARD 120 * 6 = 720.00
        String price = bookingService.calculateRoomPrice("STANDARD", 6, "NORMAL", "NONE");
        assertEquals("720.00", price);
    }

    @Test
    void calculateRoomPrice_fifteenNights_appliesBiweeklyDiscount() {
        // STANDARD 120 * 0.90 * 15 = 1620.00
        String price = bookingService.calculateRoomPrice("STANDARD", 15, "NORMAL", "NONE");
        assertEquals("1620.00", price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — combined multipliers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_peakSeason_goldLoyalty_7Nights() {
        // SUITE 350 * 1.5 (PEAK) * 0.9 (GOLD) * 0.95 (7nights) * 7 = 2,500.425 → 2500.43
        double base = 350.0 * 1.5 * 0.9 * 0.95 * 7;
        String expected = String.format("%.2f", base);
        String price = bookingService.calculateRoomPrice("SUITE", 7, "PEAK", "GOLD");
        assertEquals(expected, price);
    }

    @Test
    void calculateRoomPrice_offSeason_diamondLoyalty_14Nights() {
        // VILLA 600 * 0.8 (OFF) * 0.7 (DIAMOND) * 0.90 (14nights) * 14
        double base = 600.0 * 0.8 * 0.7 * 0.90 * 14;
        String expected = String.format("%.2f", base);
        String price = bookingService.calculateRoomPrice("VILLA", 14, "OFF", "DIAMOND");
        assertEquals(expected, price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isRoomAvailable
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void isRoomAvailable_standardRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
    }

    @Test
    void isRoomAvailable_deluxeRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
    }

    @Test
    void isRoomAvailable_suiteRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    void isRoomAvailable_villaRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_unknownRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_emptyString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_nullRoomType_returnsFalse() {
        // Java switch expression throws NullPointerException for null input — verify that behaviour
        assertThrows(NullPointerException.class, () -> bookingService.isRoomAvailable(null));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateReport_withValidMonth_returnsNonNullString() {
        String result = bookingService.generateReport("2024-03");
        assertNotNull(result);
    }

    @Test
    void generateReport_withValidMonth_containsMonthInResult() {
        String result = bookingService.generateReport("2024-03");
        assertTrue(result.contains("2024-03"));
    }

    @Test
    void generateReport_withValidMonth_containsPaymentApiUrl() {
        String result = bookingService.generateReport("2024-03");
        assertTrue(result.contains("http://10.0.1.45:9090/payments/charge"));
    }

    @Test
    void generateReport_withDifferentMonth_containsThatMonth() {
        String result = bookingService.generateReport("2024-12");
        assertTrue(result.contains("2024-12"));
    }

    @Test
    void generateReport_withEmptyMonth_returnsStringWithEmptyMonth() {
        String result = bookingService.generateReport("");
        assertNotNull(result);
    }
}
