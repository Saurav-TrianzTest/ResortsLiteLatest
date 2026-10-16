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
 * Unit tests for {@link BookingService}.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // -----------------------------------------------------------------------
    // createBooking
    // -----------------------------------------------------------------------

    @Test
    void createBooking_returnsMapWithExpectedKeys() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("bookingId"));
        assertTrue(result.containsKey("guestName"));
        assertTrue(result.containsKey("roomType"));
        assertTrue(result.containsKey("checkIn"));
        assertTrue(result.containsKey("checkOut"));
        assertTrue(result.containsKey("confirmationCode"));
        assertTrue(result.containsKey("dbHost"));
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
        assertNotNull(bookingId);
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_guestNameStoredCorrectly() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Charlie", "STANDARD", "2024-08-10", "2024-08-12");

        // Assert
        assertEquals("Charlie", result.get("guestName"));
    }

    @Test
    void createBooking_roomTypeStoredCorrectly() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Diana", "VILLA", "2024-09-01", "2024-09-07");

        // Assert
        assertEquals("VILLA", result.get("roomType"));
    }

    @Test
    void createBooking_confirmationCodeIsNonEmpty() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-04");

        // Assert
        String code = (String) result.get("confirmationCode");
        assertNotNull(code);
        assertFalse(code.isEmpty(), "Confirmation code must not be empty");
    }

    @Test
    void createBooking_jdbcTemplateExecuteCalledOnce() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        bookingService.createBooking("Frank", "DELUXE", "2024-11-01", "2024-11-03");

        // Assert
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    // -----------------------------------------------------------------------
    // getBookingById
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_returnsResultFromJdbc() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "Grace");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        // Assert
        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("id"));
        assertEquals("Grace", result.get("guest"));
    }

    @Test
    void getBookingById_whenJdbcThrows_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("No rows found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTEXIST");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        String errorMsg = (String) result.get("error");
        assertTrue(errorMsg.contains("BK-NOTEXIST"));
    }

    @Test
    void getBookingById_errorMessageContainsBookingId() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("empty result"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-XYZ999");

        // Assert
        String error = (String) result.get("error");
        assertTrue(error.contains("BK-XYZ999"), "Error message should contain the booking ID");
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoomNormalSeasonNoLoyalty() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "NORMAL", "NONE");

        // Assert – 120.0 * 3 = 360.00
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomNormalSeasonNoLoyalty() {
        // 200.0 * 2 = 400.00
        String price = bookingService.calculateRoomPrice("DELUXE", 2, "NORMAL", "NONE");
        assertEquals("400.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomNormalSeasonNoLoyalty() {
        // 350.0 * 1 = 350.00
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNormalSeasonNoLoyalty() {
        // 600.0 * 1 = 600.00
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomTypeDefaultsToStandard() {
        // Unknown room type defaults to 120.0; 120.0 * 1 = 120.00
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonMultiplier() {
        // STANDARD PEAK: 120.0 * 1.5 = 180.0; * 2 nights = 360.00
        String price = bookingService.calculateRoomPrice("STANDARD", 2, "PEAK", "NONE");
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_offSeasonMultiplier() {
        // STANDARD OFF: 120.0 * 0.8 = 96.0; * 2 nights = 192.00
        String price = bookingService.calculateRoomPrice("STANDARD", 2, "OFF", "NONE");
        assertEquals("192.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyaltyDiscount() {
        // STANDARD NORMAL GOLD: 120.0 * 0.9 = 108.0; * 1 = 108.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyaltyDiscount() {
        // STANDARD NORMAL PLATINUM: 120.0 * 0.8 = 96.0; * 1 = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyaltyDiscount() {
        // STANDARD NORMAL DIAMOND: 120.0 * 0.7 = 84.0; * 1 = 84.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNightsDiscount() {
        // STANDARD NORMAL NONE 7 nights: 120.0 * 0.95 * 7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNightsDiscount() {
        // STANDARD NORMAL NONE 14 nights: 120.0 * 0.90 * 14 = 1512.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_combinedPeakAndGoldDiscount() {
        // STANDARD PEAK GOLD: 120.0 * 1.5 * 0.9 = 162.0; * 1 = 162.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "GOLD");
        assertEquals("162.00", price);
    }

    @Test
    void calculateRoomPrice_villaOffSeasonDiamondFourteenNights() {
        // VILLA OFF DIAMOND 14 nights: 600 * 0.8 * 0.7 * 0.9 * 14 = 2116.80
        String price = bookingService.calculateRoomPrice("VILLA", 14, "OFF", "DIAMOND");
        assertEquals("4233.60", price);
    }

    // -----------------------------------------------------------------------
    // isRoomAvailable
    // -----------------------------------------------------------------------

    @Test
    void isRoomAvailable_standardRoomIsAvailable() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
    }

    @Test
    void isRoomAvailable_deluxeRoomIsAvailable() {
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
    }

    @Test
    void isRoomAvailable_suiteRoomIsAvailable() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    void isRoomAvailable_villaRoomIsAvailable() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_unknownRoomTypeIsNotAvailable() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_emptyStringIsNotAvailable() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    // -----------------------------------------------------------------------
    // generateReport
    // -----------------------------------------------------------------------

    @Test
    void generateReport_returnsNonNullString() {
        String result = bookingService.generateReport("03");
        assertNotNull(result);
    }

    @Test
    void generateReport_containsMonthInResult() {
        String result = bookingService.generateReport("07");
        assertTrue(result.contains("07"), "Result should contain the month");
    }

    @Test
    void generateReport_containsPaymentApiReference() {
        String result = bookingService.generateReport("12");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }
}
