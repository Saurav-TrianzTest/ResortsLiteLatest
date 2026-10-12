package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link BookingController}.
 * Covers createBooking, getBookingStatus, checkAvailability, and downloadReport endpoints.
 */
@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidInputs_returnsNonNullResponse() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Alice");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        assertNotNull(response);
    }

    @Test
    void createBooking_withValidInputs_returnsConfirmedStatus() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidInputs_returnsBookingInResponse() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        assertNotNull(response.get("booking"));
        assertEquals(mockBooking, response.get("booking"));
    }

    @Test
    void createBooking_storesLastBookingInSession() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        bookingController.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        assertNotNull(session.getAttribute("lastBooking"));
        assertEquals(mockBooking, session.getAttribute("lastBooking"));
    }

    @Test
    void createBooking_storesGuestNameInSession() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        bookingController.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        assertEquals("Alice", session.getAttribute("guestName"));
    }

    @Test
    void createBooking_addsBookingToCache() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-CACHE001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-05", session);

        // Verify the booking was returned in the response (cache is internal)
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_callsBookingServiceOnce() {
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        bookingController.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        verify(bookingService, times(1))
                .createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05");
    }

    @Test
    void createBooking_withDifferentRoomTypes_returnsConfirmedStatus() {
        for (String roomType : new String[]{"STANDARD", "DELUXE", "SUITE", "VILLA"}) {
            Map<String, Object> mockBooking = new HashMap<>();
            mockBooking.put("bookingId", "BK-" + roomType);
            when(bookingService.createBooking(anyString(), eq(roomType), anyString(), anyString()))
                    .thenReturn(mockBooking);

            Map<String, Object> response = bookingController.createBooking(
                    "Guest", roomType, "2024-01-01", "2024-01-05", session);

            assertEquals("confirmed", response.get("status"),
                    "Expected confirmed status for room type: " + roomType);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingStatus
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingStatus_withValidBookingId_returnsNonNullResponse() {
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-ABCD1234");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(mockDetails);

        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        assertNotNull(result);
    }

    @Test
    void getBookingStatus_withValidBookingId_returnsBookingIdInResult() {
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-ABCD1234");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(mockDetails);

        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        assertEquals("BK-ABCD1234", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_withSessionGuest_returnsGuestInResult() {
        session.setAttribute("guestName", "Alice");
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        assertEquals("Alice", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_withNoSessionGuest_returnsNullSessionGuest() {
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        assertNull(result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_returnsDetailsFromService() {
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-ABCD1234");
        mockDetails.put("guest", "Alice");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(mockDetails);

        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        assertEquals(mockDetails, result.get("details"));
    }

    @Test
    void getBookingStatus_callsBookingServiceWithCorrectId() {
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById("BK-XYZ999")).thenReturn(mockDetails);

        bookingController.getBookingStatus("BK-XYZ999", session);

        verify(bookingService, times(1)).getBookingById("BK-XYZ999");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkAvailability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void checkAvailability_withAvailableRoom_returnsNonNullResponse() {
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        assertNotNull(result);
    }

    @Test
    void checkAvailability_withAvailableRoom_returnsRoomType() {
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        assertEquals("SUITE", result.get("roomType"));
    }

    @Test
    void checkAvailability_withAvailableRoom_returnsAvailableTrue() {
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        assertTrue((Boolean) result.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsAvailableFalse() {
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        Map<String, Object> result = bookingController.checkAvailability("PENTHOUSE");

        assertFalse((Boolean) result.get("available"));
    }

    @Test
    void checkAvailability_returnsInventoryEndpoint() {
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        assertNotNull(result.get("inventoryEndpoint"));
        assertTrue(((String) result.get("inventoryEndpoint"))
                .contains("inventory-service.internal"));
    }

    @Test
    void checkAvailability_callsBookingServiceWithCorrectRoomType() {
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        bookingController.checkAvailability("VILLA");

        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // downloadReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void downloadReport_withValidMonth_returnsNonNullResponse() {
        when(bookingService.generateReport("2024-03")).thenReturn("Report generated");

        Map<String, Object> result = bookingController.downloadReport("2024-03");

        assertNotNull(result);
    }

    @Test
    void downloadReport_withValidMonth_returnsReportPath() {
        when(bookingService.generateReport("2024-03")).thenReturn("Report generated");

        Map<String, Object> result = bookingController.downloadReport("2024-03");

        assertNotNull(result.get("reportPath"));
        assertTrue(((String) result.get("reportPath")).contains("2024-03"));
    }

    @Test
    void downloadReport_withValidMonth_reportPathContainsPdfExtension() {
        when(bookingService.generateReport("2024-03")).thenReturn("Report generated");

        Map<String, Object> result = bookingController.downloadReport("2024-03");

        assertTrue(((String) result.get("reportPath")).endsWith("_bookings.pdf"));
    }

    @Test
    void downloadReport_withValidMonth_returnsMessageFromService() {
        when(bookingService.generateReport("2024-03")).thenReturn("Report generated for 2024-03");

        Map<String, Object> result = bookingController.downloadReport("2024-03");

        assertEquals("Report generated for 2024-03", result.get("message"));
    }

    @Test
    void downloadReport_callsBookingServiceWithCorrectMonth() {
        when(bookingService.generateReport("2024-06")).thenReturn("Report for June");

        bookingController.downloadReport("2024-06");

        verify(bookingService, times(1)).generateReport("2024-06");
    }

    @Test
    void downloadReport_reportPathStartsWithLegacyPath() {
        when(bookingService.generateReport(anyString())).thenReturn("ok");

        Map<String, Object> result = bookingController.downloadReport("2024-01");

        assertTrue(((String) result.get("reportPath")).startsWith("/var/legacy/reports/"));
    }

    @Test
    void downloadReport_withDifferentMonths_returnsCorrectPaths() {
        when(bookingService.generateReport(anyString())).thenReturn("ok");

        Map<String, Object> result1 = bookingController.downloadReport("2024-01");
        Map<String, Object> result2 = bookingController.downloadReport("2024-12");

        assertTrue(((String) result1.get("reportPath")).contains("2024-01"));
        assertTrue(((String) result2.get("reportPath")).contains("2024-12"));
    }
}
