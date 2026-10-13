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
    void createBooking_withValidInputs_returnsBookingMapWithExpectedKeys() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("bookingId"));
        assertTrue(result.containsKey("guestName"));
        assertTrue(result.containsKey("roomType"));
        assertTrue(result.containsKey("checkIn"));
        assertTrue(result.containsKey("checkOut"));
        assertTrue(result.containsKey("confirmationCode"));
    }

    @Test
    void createBooking_withValidInputs_bookingIdStartsWithBK() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Jane Doe", "DELUXE", "2024-04-01", "2024-04-07");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertNotNull(bookingId);
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_withValidInputs_guestNameStoredCorrectly() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice Wonderland", "VILLA", "2024-05-01", "2024-05-10");

        // Assert
        assertEquals("Alice Wonderland", result.get("guestName"));
        assertEquals("VILLA", result.get("roomType"));
        assertEquals("2024-05-01", result.get("checkIn"));
        assertEquals("2024-05-10", result.get("checkOut"));
    }

    @Test
    void createBooking_withValidInputs_confirmationCodeIsNotEmpty() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob Builder", "STANDARD", "2024-06-01", "2024-06-03");

        // Assert
        String confirmCode = (String) result.get("confirmationCode");
        assertNotNull(confirmCode);
        assertFalse(confirmCode.isEmpty(), "Confirmation code should not be empty");
    }

    @Test
    void createBooking_jdbcTemplateCalledWithParameterisedQuery() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Test Guest", "SUITE", "2024-07-01", "2024-07-05");

        // Assert
        verify(jdbcTemplate, times(1)).update(
                eq("INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)"),
                anyString(), eq("Test Guest"), eq("SUITE"), eq("2024-07-01"), eq("2024-07-05"));
    }

    @Test
    void createBooking_twoCallsProduceDifferentBookingIds() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> booking1 = bookingService.createBooking(
                "Guest One", "SUITE", "2024-01-01", "2024-01-05");
        Map<String, Object> booking2 = bookingService.createBooking(
                "Guest Two", "DELUXE", "2024-02-01", "2024-02-05");

        // Assert
        assertNotEquals(booking1.get("bookingId"), booking2.get("bookingId"),
                "Each booking should have a unique ID");
    }

    // -----------------------------------------------------------------------
    // getBookingById tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_whenBookingExists_returnsBookingData() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "John Smith");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-ABCD1234"))).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        // Assert
        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("id"));
        assertEquals("John Smith", result.get("guest"));
    }

    @Test
    void getBookingById_whenBookingNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-NOTFOUND")))
                .thenThrow(new RuntimeException("No results found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTFOUND");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"), "Result should contain 'error' key");
        String errorMsg = (String) result.get("error");
        assertTrue(errorMsg.contains("BK-NOTFOUND"), "Error message should contain the booking ID");
    }

    @Test
    void getBookingById_usesParameterisedQuery() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-TEST001"))).thenReturn(new HashMap<>());

        // Act
        bookingService.getBookingById("BK-TEST001");

        // Assert
        verify(jdbcTemplate, times(1)).queryForMap(
                eq("SELECT * FROM bookings WHERE id = ?"), eq("BK-TEST001"));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice tests
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoomNormalSeasonNoLoyalty_returnsBasePrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomNormalSeasonNoLoyalty_returnsBasePrice() {
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomNormalSeasonNoLoyalty_returnsBasePrice() {
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNormalSeasonNoLoyalty_returnsBasePrice() {
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_usesDefaultBasePrice() {
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeason_appliesOnePointFiveMultiplier() {
        // SUITE base=350, PEAK=350*1.5=525, 1 night => 525.00
        String price = bookingService.calculateRoomPrice("SUITE", 1, "PEAK", "NONE");
        assertEquals("525.00", price);
    }

    @Test
    void calculateRoomPrice_offSeason_appliesZeroPointEightMultiplier() {
        // SUITE base=350, OFF=350*0.8=280, 1 night => 280.00
        String price = bookingService.calculateRoomPrice("SUITE", 1, "OFF", "NONE");
        assertEquals("280.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyalty_appliesTenPercentDiscount() {
        // DELUXE base=200, GOLD=200*0.9=180, 1 night => 180.00
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "GOLD");
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_appliesTwentyPercentDiscount() {
        // DELUXE base=200, PLATINUM=200*0.8=160, 1 night => 160.00
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "PLATINUM");
        assertEquals("160.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_appliesThirtyPercentDiscount() {
        // DELUXE base=200, DIAMOND=200*0.7=140, 1 night => 140.00
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "DIAMOND");
        assertEquals("140.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNights_appliesFivePercentDiscount() {
        // STANDARD base=120, 7 nights discount=0.95, total=120*0.95*7=798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesTenPercentDiscount() {
        // STANDARD base=120, 14 nights discount=0.90, total=120*0.90*14=1512.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_combinedPeakSeasonGoldLoyaltySevenNights() {
        // SUITE base=350, PEAK=350*1.5=525, GOLD=525*0.9=472.5, 7nights=472.5*0.95=448.875, *7=3142.125 => 3142.13
        String price = bookingService.calculateRoomPrice("SUITE", 7, "PEAK", "GOLD");
        assertEquals("3142.13", price);
    }

    @Test
    void calculateRoomPrice_multipleNightsNoDiscount_returnsCorrectTotal() {
        // STANDARD base=120, 3 nights => 360.00
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "NORMAL", "NONE");
        assertEquals("360.00", price);
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
    void isRoomAvailable_nullRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(null));
    }

    @Test
    void isRoomAvailable_lowercaseRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    // -----------------------------------------------------------------------
    // generateReport tests
    // -----------------------------------------------------------------------

    @Test
    void generateReport_withValidMonth_returnsNonNullString() {
        String result = bookingService.generateReport("March");
        assertNotNull(result);
    }

    @Test
    void generateReport_withValidMonth_containsMonthInResult() {
        String result = bookingService.generateReport("March");
        assertTrue(result.contains("March"), "Result should contain the month name");
    }

    @Test
    void generateReport_withNumericMonth_containsMonthInResult() {
        String result = bookingService.generateReport("03");
        assertTrue(result.contains("03"));
    }

    @Test
    void generateReport_withEmptyMonth_returnsStringContainingTriggerMessage() {
        String result = bookingService.generateReport("");
        assertNotNull(result);
        assertTrue(result.contains("Report generation triggered for:"));
    }
}
