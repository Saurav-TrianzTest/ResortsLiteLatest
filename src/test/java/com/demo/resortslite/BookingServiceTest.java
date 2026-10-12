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
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertEquals("Alice", result.get("guestName"));
        assertEquals("SUITE", result.get("roomType"));
        assertEquals("2024-06-01", result.get("checkIn"));
        assertEquals("2024-06-05", result.get("checkOut"));
        assertNotNull(result.get("bookingId"));
        assertNotNull(result.get("confirmationCode"));
    }

    @Test
    void createBooking_bookingIdStartsWithBK() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with BK-");
    }

    @Test
    void createBooking_confirmationCodeIsNotEmpty() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-08-10", "2024-08-12");

        // Assert
        String confirmCode = (String) result.get("confirmationCode");
        assertNotNull(confirmCode);
        assertFalse(confirmCode.isEmpty(), "Confirmation code should not be empty");
    }

    @Test
    void createBooking_dbHostIsPresent() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-07");

        // Assert
        assertNotNull(result.get("dbHost"));
    }

    @Test
    void createBooking_jdbcUpdateIsCalled() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-04");

        // Assert
        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), any(), any());
    }

    // -----------------------------------------------------------------------
    // getBookingById tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_withExistingId_returnsBookingData() {
        // Arrange
        Map<String, Object> mockRow = new HashMap<>();
        mockRow.put("id", "BK-12345678");
        mockRow.put("guest", "Alice");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-12345678"))).thenReturn(mockRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-12345678");

        // Assert
        assertNotNull(result);
        assertEquals("BK-12345678", result.get("id"));
        assertEquals("Alice", result.get("guest"));
    }

    @Test
    void getBookingById_withNonExistingId_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-NOTFOUND")))
                .thenThrow(new RuntimeException("No row found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTFOUND");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"), "Result should contain an error key");
        assertTrue(result.get("error").toString().contains("BK-NOTFOUND"));
    }

    @Test
    void getBookingById_exceptionMessage_containsBookingId() {
        // Arrange
        String missingId = "BK-MISSING";
        when(jdbcTemplate.queryForMap(anyString(), eq(missingId)))
                .thenThrow(new RuntimeException("EmptyResultDataAccessException"));

        // Act
        Map<String, Object> result = bookingService.getBookingById(missingId);

        // Assert
        String errorMsg = (String) result.get("error");
        assertTrue(errorMsg.contains(missingId));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice tests — room type branch
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_standardSeason_noLoyalty_oneNight() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "STANDARD", "NONE");

        // Assert
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoom_standardSeason_noLoyalty_oneNight() {
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "STANDARD", "NONE");
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoom_standardSeason_noLoyalty_oneNight() {
        String price = bookingService.calculateRoomPrice("SUITE", 1, "STANDARD", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoom_standardSeason_noLoyalty_oneNight() {
        String price = bookingService.calculateRoomPrice("VILLA", 1, "STANDARD", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_defaultsToStandard() {
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "STANDARD", "NONE");
        assertEquals("120.00", price);
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice tests — season branch
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_peakSeason_noLoyalty_oneNight() {
        // 120 * 1.5 = 180
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoom_offSeason_noLoyalty_oneNight() {
        // 120 * 0.8 = 96
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");
        assertEquals("96.00", price);
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice tests — loyalty branch
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_standardSeason_goldLoyalty_oneNight() {
        // 120 * 0.9 = 108
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "STANDARD", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoom_standardSeason_platinumLoyalty_oneNight() {
        // 120 * 0.8 = 96
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "STANDARD", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoom_standardSeason_diamondLoyalty_oneNight() {
        // 120 * 0.7 = 84
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "STANDARD", "DIAMOND");
        assertEquals("84.00", price);
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice tests — nights discount branch
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_sevenNights_fivePercentDiscount() {
        // 120 * 0.95 * 7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "STANDARD", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoom_fourteenNights_tenPercentDiscount() {
        // 120 * 0.90 * 14 = 1512.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "STANDARD", "NONE");
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoom_twentyNights_tenPercentDiscount() {
        // 120 * 0.90 * 20 = 2160.00
        String price = bookingService.calculateRoomPrice("STANDARD", 20, "STANDARD", "NONE");
        assertEquals("2160.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoom_peakSeason_diamondLoyalty_fourteenNights() {
        // 600 * 1.5 = 900; * 0.7 = 630; * 0.90 = 567; * 14 = 7938.00
        String price = bookingService.calculateRoomPrice("VILLA", 14, "PEAK", "DIAMOND");
        assertEquals("7938.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoom_offSeason_platinumLoyalty_sevenNights() {
        // 350 * 0.8 = 280; * 0.8 = 224; * 0.95 = 212.8; * 7 = 1489.60
        String price = bookingService.calculateRoomPrice("SUITE", 7, "OFF", "PLATINUM");
        assertEquals("1489.60", price);
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
    void isRoomAvailable_nullLikeString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("null"));
    }

    // -----------------------------------------------------------------------
    // generateReport tests
    // -----------------------------------------------------------------------

    @Test
    void generateReport_returnsNonNullMessage() {
        String result = bookingService.generateReport("March");
        assertNotNull(result);
    }

    @Test
    void generateReport_containsMonthInMessage() {
        String result = bookingService.generateReport("March");
        assertTrue(result.contains("March"), "Report message should contain the month");
    }

    @Test
    void generateReport_containsTriggeredKeyword() {
        String result = bookingService.generateReport("January");
        assertTrue(result.toLowerCase().contains("triggered") ||
                   result.toLowerCase().contains("report"),
                "Report message should indicate generation was triggered");
    }

    @Test
    void generateReport_differentMonths_returnsDifferentMessages() {
        String march = bookingService.generateReport("March");
        String april = bookingService.generateReport("April");
        assertNotEquals(march, april);
    }
}
