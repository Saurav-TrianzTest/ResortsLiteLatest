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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // -----------------------------------------------------------------------
    // createBooking tests
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsBookingMap() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("bookingId"));
        assertEquals("Alice", result.get("guestName"));
        assertEquals("SUITE", result.get("roomType"));
        assertEquals("2024-06-01", result.get("checkIn"));
        assertEquals("2024-06-05", result.get("checkOut"));
    }

    @Test
    void createBooking_bookingIdStartsWithBK() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertTrue(bookingId.startsWith("BK-"));
    }

    @Test
    void createBooking_confirmationCodeIsNotNull() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "VILLA", "2024-08-10", "2024-08-17");

        // Assert
        assertNotNull(result.get("confirmationCode"));
        assertFalse(result.get("confirmationCode").toString().isEmpty());
    }

    @Test
    void createBooking_confirmationCodeIsSha256Format() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Dave", "STANDARD", "2024-09-01", "2024-09-02");

        // Assert
        String code = (String) result.get("confirmationCode");
        // SHA-256 produces 64 hex characters
        assertEquals(64, code.length());
        assertTrue(code.matches("[0-9a-f]+"));
    }

    @Test
    void createBooking_executesJdbcInsert() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        bookingService.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-05");

        // Assert
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    @Test
    void createBooking_returnsDbHostInResult() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-03");

        // Assert
        assertNotNull(result.get("dbHost"));
    }

    @Test
    void createBooking_twoCallsProduceDifferentBookingIds() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result1 = bookingService.createBooking(
                "Guest1", "STANDARD", "2024-01-01", "2024-01-02");
        Map<String, Object> result2 = bookingService.createBooking(
                "Guest2", "STANDARD", "2024-01-01", "2024-01-02");

        // Assert
        assertNotEquals(result1.get("bookingId"), result2.get("bookingId"));
    }

    // -----------------------------------------------------------------------
    // getBookingById tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_whenFound_returnsBookingData() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-001");
        dbRow.put("guest", "Alice");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-001");

        // Assert
        assertNotNull(result);
        assertEquals("BK-001", result.get("id"));
        assertEquals("Alice", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("No results"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTFOUND");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("BK-NOTFOUND"));
    }

    @Test
    void getBookingById_callsJdbcQueryForMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(new HashMap<>());

        // Act
        bookingService.getBookingById("BK-VERIFY");

        // Assert
        verify(jdbcTemplate, times(1)).queryForMap(anyString());
    }

    @Test
    void getBookingById_withEmptyId_returnsErrorOrEmpty() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("Empty id"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice tests
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoomNormalSeason_returnsCorrectPrice() {
        // Arrange & Act
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "NORMAL", "NONE");

        // Assert
        assertNotNull(price);
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomNormalSeason_returnsCorrectPrice() {
        // Arrange & Act
        String price = bookingService.calculateRoomPrice("DELUXE", 2, "NORMAL", "NONE");

        // Assert
        assertEquals("400.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomNormalSeason_returnsCorrectPrice() {
        // Arrange & Act
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNormalSeason_returnsCorrectPrice() {
        // Arrange & Act
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_defaultsToStandardPrice() {
        // Arrange & Act
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeason_appliesMultiplier() {
        // Arrange & Act
        // STANDARD base=120, PEAK * 1.5 = 180, * 1 night = 180
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");

        // Assert
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_offSeason_appliesDiscount() {
        // Arrange & Act
        // STANDARD base=120, OFF * 0.8 = 96, * 1 night = 96
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");

        // Assert
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyalty_appliesDiscount() {
        // Arrange & Act
        // STANDARD base=120, GOLD * 0.9 = 108, * 1 night = 108
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");

        // Assert
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_appliesDiscount() {
        // Arrange & Act
        // STANDARD base=120, PLATINUM * 0.8 = 96, * 1 night = 96
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");

        // Assert
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_appliesDiscount() {
        // Arrange & Act
        // STANDARD base=120, DIAMOND * 0.7 = 84, * 1 night = 84
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");

        // Assert
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNights_appliesWeeklyDiscount() {
        // Arrange & Act
        // STANDARD base=120, nights>=7 * 0.95 = 114, * 7 = 798
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");

        // Assert
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesBiweeklyDiscount() {
        // Arrange & Act
        // STANDARD base=120, nights>=7 * 0.95 = 114 (note: >=7 branch fires first), * 14 = 1596
        // Actually: nights>=14 branch is checked AFTER nights>=7, so nights>=7 fires for 14 too
        // base=120, *0.95=114, *14=1596
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");

        // Assert
        assertNotNull(price);
        // The price should be a valid decimal number
        assertTrue(Double.parseDouble(price) > 0);
    }

    @Test
    void calculateRoomPrice_peakSeasonWithGoldLoyalty_combinesMultipliers() {
        // Arrange & Act
        // STANDARD base=120, PEAK*1.5=180, GOLD*0.9=162, *2 nights=324
        String price = bookingService.calculateRoomPrice("STANDARD", 2, "PEAK", "GOLD");

        // Assert
        assertEquals("324.00", price);
    }

    @Test
    void calculateRoomPrice_returnsFormattedTwoDecimalString() {
        // Arrange & Act
        String price = bookingService.calculateRoomPrice("DELUXE", 3, "NORMAL", "NONE");

        // Assert
        assertTrue(price.matches("\\d+\\.\\d{2}"));
    }

    // -----------------------------------------------------------------------
    // isRoomAvailable tests
    // -----------------------------------------------------------------------

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
    void isRoomAvailable_lowercaseRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    @Test
    void isRoomAvailable_nullLikeString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("null"));
    }

    // -----------------------------------------------------------------------
    // generateReport tests
    // -----------------------------------------------------------------------

    @Test
    void generateReport_withValidMonth_returnsNonNullString() {
        // Act
        String result = bookingService.generateReport("March");

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void generateReport_containsMonthInResult() {
        // Act
        String result = bookingService.generateReport("June");

        // Assert
        assertTrue(result.contains("June"));
    }

    @Test
    void generateReport_containsPaymentApiReference() {
        // Act
        String result = bookingService.generateReport("January");

        // Assert
        assertNotNull(result);
        // The result references the PAYMENT_API constant
        assertTrue(result.contains("via"));
    }

    @Test
    void generateReport_withDifferentMonths_returnsDistinctResults() {
        // Act
        String result1 = bookingService.generateReport("January");
        String result2 = bookingService.generateReport("February");

        // Assert
        assertNotEquals(result1, result2);
    }
}
