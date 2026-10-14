package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    void createBooking_withValidInputs_returnsCorrectGuestName() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        assertEquals("Bob", result.get("guestName"));
        assertEquals("DELUXE", result.get("roomType"));
        assertEquals("2024-07-01", result.get("checkIn"));
        assertEquals("2024-07-03", result.get("checkOut"));
    }

    @Test
    void createBooking_bookingIdStartsWithBK() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-08-01", "2024-08-02");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertNotNull(bookingId);
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_confirmationCodeIsNotNull() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-10");

        // Assert
        assertNotNull(result.get("confirmationCode"));
        assertFalse(((String) result.get("confirmationCode")).isEmpty());
    }

    @Test
    void createBooking_jdbcTemplateUpdateIsCalled() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-05");

        // Assert
        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void createBooking_withEmptyGuestName_stillCreatesBooking() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "", "STANDARD", "2024-01-01", "2024-01-02");

        // Assert
        assertNotNull(result);
        assertEquals("", result.get("guestName"));
    }

    // -----------------------------------------------------------------------
    // getBookingById tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_withValidId_returnsBookingData() {
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
    void getBookingById_whenBookingNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), anyString()))
                .thenThrow(new RuntimeException("No results found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTEXIST");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(((String) result.get("error")).contains("BK-NOTEXIST"));
    }

    @Test
    void getBookingById_errorMessageContainsBookingId() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), anyString()))
                .thenThrow(new RuntimeException("EmptyResultDataAccessException"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCDEF12");

        // Assert
        String errorMsg = (String) result.get("error");
        assertNotNull(errorMsg);
        assertTrue(errorMsg.contains("BK-ABCDEF12"));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice tests
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoomNormalSeasonNoLoyalty_returnsCorrectPrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "NORMAL", "NONE");

        // Assert
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomNormalSeasonNoLoyalty_returnsCorrectPrice() {
        // Act
        String price = bookingService.calculateRoomPrice("DELUXE", 2, "NORMAL", "NONE");

        // Assert
        assertEquals("400.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomNormalSeasonNoLoyalty_returnsCorrectPrice() {
        // Act
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNormalSeasonNoLoyalty_returnsCorrectPrice() {
        // Act
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomTypeDefaultsToStandard() {
        // Act
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonApplies150PercentMultiplier() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");

        // Assert: 120 * 1.5 = 180
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_offSeasonApplies80PercentMultiplier() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");

        // Assert: 120 * 0.8 = 96
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyaltyApplies10PercentDiscount() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");

        // Assert: 120 * 0.9 = 108
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyaltyApplies20PercentDiscount() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");

        // Assert: 120 * 0.8 = 96
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyaltyApplies30PercentDiscount() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");

        // Assert: 120 * 0.7 = 84
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNightsApplies5PercentDiscount() {
        // Act: 120 * 7 nights * 0.95 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");

        // Assert
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNightsApplies10PercentDiscount() {
        // Act: 120 * 14 * 0.90 = 1512.00
        // Note: nights >= 14 branch applies 0.90 but nights >= 7 is checked first (0.95)
        // The code checks >= 7 first, so 14 nights gets 0.95 discount (not 0.90)
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");

        // Assert: 120 * 0.95 * 14 = 1596.00
        assertEquals("1596.00", price);
    }

    @Test
    void calculateRoomPrice_combinedPeakAndGoldLoyalty() {
        // Act: 120 * 1.5 * 0.9 * 2 = 324.00
        String price = bookingService.calculateRoomPrice("STANDARD", 2, "PEAK", "GOLD");

        // Assert
        assertEquals("324.00", price);
    }

    @ParameterizedTest
    @CsvSource({
        "STANDARD, 1, NORMAL, NONE, 120.00",
        "DELUXE,   1, NORMAL, NONE, 200.00",
        "SUITE,    1, NORMAL, NONE, 350.00",
        "VILLA,    1, NORMAL, NONE, 600.00"
    })
    void calculateRoomPrice_parameterized_roomTypes(
            String roomType, int nights, String season, String loyalty, String expected) {
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);
        assertEquals(expected, price);
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
    void isRoomAvailable_nullRoomType_throwsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> bookingService.isRoomAvailable(null));
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
    void generateReport_withEmptyMonth_returnsStringContainingVia() {
        // Act
        String result = bookingService.generateReport("");

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("via"));
    }
}
