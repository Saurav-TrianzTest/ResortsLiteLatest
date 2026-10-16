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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BookingController}.
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

    // -----------------------------------------------------------------------
    // createBooking
    // -----------------------------------------------------------------------

    @Test
    void createBooking_returnsNonNullResponse() {
        // Arrange
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-TESTID1");
        booking.put("guestName", "Alice");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(booking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        assertNotNull(response);
    }

    @Test
    void createBooking_responseContainsStatusConfirmed() {
        // Arrange
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-TESTID2");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(booking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03", session);

        // Assert
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_responseContainsBookingObject() {
        // Arrange
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-TESTID3");
        booking.put("guestName", "Charlie");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(booking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Charlie", "STANDARD", "2024-08-01", "2024-08-04", session);

        // Assert
        assertTrue(response.containsKey("booking"));
        assertNotNull(response.get("booking"));
    }

    @Test
    void createBooking_setsSessionAttributeLastBooking() {
        // Arrange
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-SESS001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(booking);

        // Act
        bookingController.createBooking(
                "Diana", "VILLA", "2024-09-01", "2024-09-07", session);

        // Assert
        assertNotNull(session.getAttribute("lastBooking"),
                "Session should have 'lastBooking' attribute set");
    }

    @Test
    void createBooking_setsSessionAttributeGuestName() {
        // Arrange
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-SESS002");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(booking);

        // Act
        bookingController.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-04", session);

        // Assert
        assertEquals("Eve", session.getAttribute("guestName"),
                "Session should store the guest name");
    }

    @Test
    void createBooking_callsBookingServiceOnce() {
        // Arrange
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-CALL001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(booking);

        // Act
        bookingController.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-03", session);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Frank", "DELUXE", "2024-11-01", "2024-11-03");
    }

    // -----------------------------------------------------------------------
    // getBookingStatus
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_returnsNonNullResult() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        details.put("id", "BK-STATUS1");
        when(bookingService.getBookingById("BK-STATUS1")).thenReturn(details);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS1", session);

        // Assert
        assertNotNull(result);
    }

    @Test
    void getBookingStatus_resultContainsBookingId() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        when(bookingService.getBookingById("BK-STATUS2")).thenReturn(details);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS2", session);

        // Assert
        assertEquals("BK-STATUS2", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_resultContainsDetailsKey() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        details.put("guest", "Grace");
        when(bookingService.getBookingById("BK-STATUS3")).thenReturn(details);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS3", session);

        // Assert
        assertTrue(result.containsKey("details"));
    }

    @Test
    void getBookingStatus_sessionGuestNameIsNullWhenNotSet() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        when(bookingService.getBookingById("BK-STATUS4")).thenReturn(details);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS4", session);

        // Assert – session has no guestName set, so sessionGuest should be null
        assertNull(result.get("sessionGuest"),
                "sessionGuest should be null when session has no guestName");
    }

    @Test
    void getBookingStatus_sessionGuestNameReturnedWhenSet() {
        // Arrange
        session.setAttribute("guestName", "Henry");
        Map<String, Object> details = new HashMap<>();
        when(bookingService.getBookingById("BK-STATUS5")).thenReturn(details);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS5", session);

        // Assert
        assertEquals("Henry", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_callsGetBookingByIdOnce() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        when(bookingService.getBookingById("BK-VERIFY1")).thenReturn(details);

        // Act
        bookingController.getBookingStatus("BK-VERIFY1", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-VERIFY1");
    }

    // -----------------------------------------------------------------------
    // checkAvailability
    // -----------------------------------------------------------------------

    @Test
    void checkAvailability_returnsNonNullResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(response);
    }

    @Test
    void checkAvailability_responseContainsRoomType() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals("DELUXE", response.get("roomType"));
    }

    @Test
    void checkAvailability_responseContainsAvailableTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        // Assert
        assertEquals(true, response.get("available"));
    }

    @Test
    void checkAvailability_responseContainsAvailableFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, response.get("available"));
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("VILLA");

        // Assert
        assertTrue(response.containsKey("inventoryEndpoint"),
                "Response should contain 'inventoryEndpoint'");
        assertNotNull(response.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_callsIsRoomAvailableOnce() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        bookingController.checkAvailability("SUITE");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("SUITE");
    }

    // -----------------------------------------------------------------------
    // downloadReport
    // -----------------------------------------------------------------------

    @Test
    void downloadReport_returnsNonNullResponse() {
        // Arrange
        when(bookingService.generateReport("03")).thenReturn("Report for 03");

        // Act
        Map<String, Object> response = bookingController.downloadReport("03");

        // Assert
        assertNotNull(response);
    }

    @Test
    void downloadReport_responseContainsReportPath() {
        // Arrange
        when(bookingService.generateReport("05")).thenReturn("Report for 05");

        // Act
        Map<String, Object> response = bookingController.downloadReport("05");

        // Assert
        assertTrue(response.containsKey("reportPath"),
                "Response should contain 'reportPath'");
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport("07")).thenReturn("Report for 07");

        // Act
        Map<String, Object> response = bookingController.downloadReport("07");

        // Assert
        String path = (String) response.get("reportPath");
        assertTrue(path.contains("07"), "Report path should contain the month");
    }

    @Test
    void downloadReport_responseContainsMessage() {
        // Arrange
        when(bookingService.generateReport("09")).thenReturn("Report generated for 09");

        // Act
        Map<String, Object> response = bookingController.downloadReport("09");

        // Assert
        assertTrue(response.containsKey("message"),
                "Response should contain 'message'");
        assertEquals("Report generated for 09", response.get("message"));
    }

    @Test
    void downloadReport_callsGenerateReportOnce() {
        // Arrange
        when(bookingService.generateReport("11")).thenReturn("Report for 11");

        // Act
        bookingController.downloadReport("11");

        // Assert
        verify(bookingService, times(1)).generateReport("11");
    }

    @Test
    void downloadReport_reportPathEndsWith_bookings_pdf() {
        // Arrange
        when(bookingService.generateReport("12")).thenReturn("Report for 12");

        // Act
        Map<String, Object> response = bookingController.downloadReport("12");

        // Assert
        String path = (String) response.get("reportPath");
        assertTrue(path.endsWith("_bookings.pdf"),
                "Report path should end with '_bookings.pdf'");
    }
}
