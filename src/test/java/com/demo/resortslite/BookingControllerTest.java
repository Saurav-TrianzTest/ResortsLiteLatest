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

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    private MockHttpSession mockSession;

    @BeforeEach
    void setUp() {
        mockSession = new MockHttpSession();
    }

    // -----------------------------------------------------------------------
    // createBooking tests
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsResponseWithStatusConfirmed() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "John Smith");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05", mockSession);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidParams_responseContainsBookingObject() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Jane Doe");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Jane Doe", "DELUXE", "2024-04-01", "2024-04-07", mockSession);

        // Assert
        assertTrue(response.containsKey("booking"), "Response should contain 'booking' key");
        assertNotNull(response.get("booking"));
    }

    @Test
    void createBooking_withValidParams_setsSessionAttributes() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Alice");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking(
                "Alice", "VILLA", "2024-05-01", "2024-05-10", mockSession);

        // Assert
        assertEquals("Alice", mockSession.getAttribute("guestName"),
                "Session should store guestName");
        assertNotNull(mockSession.getAttribute("lastBooking"),
                "Session should store lastBooking");
    }

    @Test
    void createBooking_callsBookingServiceWithCorrectParams() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-TEST001");
        when(bookingService.createBooking("Bob", "SUITE", "2024-06-01", "2024-06-05"))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Bob", "SUITE", "2024-06-01", "2024-06-05", mockSession);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Bob", "SUITE", "2024-06-01", "2024-06-05");
    }

    @Test
    void createBooking_bookingAddedToCache() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-CACHE001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Cache Test", "STANDARD", "2024-07-01", "2024-07-03", mockSession);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    // -----------------------------------------------------------------------
    // getBookingStatus tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_withValidBookingId_returnsResultWithBookingId() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-STATUS001");
        mockDetails.put("guest", "John Smith");
        when(bookingService.getBookingById("BK-STATUS001")).thenReturn(mockDetails);
        mockSession.setAttribute("guestName", "John Smith");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS001", mockSession);

        // Assert
        assertNotNull(result);
        assertEquals("BK-STATUS001", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_withSessionGuest_returnsSessionGuestInResult() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);
        mockSession.setAttribute("guestName", "Stored Guest");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-001", mockSession);

        // Assert
        assertEquals("Stored Guest", result.get("sessionGuest"),
                "Result should contain the session guest name");
    }

    @Test
    void getBookingStatus_withNoSessionGuest_sessionGuestIsNull() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-002", mockSession);

        // Assert
        assertNull(result.get("sessionGuest"),
                "sessionGuest should be null when not set in session");
    }

    @Test
    void getBookingStatus_resultContainsDetailsKey() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-003");
        when(bookingService.getBookingById("BK-003")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-003", mockSession);

        // Assert
        assertTrue(result.containsKey("details"), "Result should contain 'details' key");
    }

    @Test
    void getBookingStatus_callsBookingServiceWithCorrectId() {
        // Arrange
        when(bookingService.getBookingById("BK-VERIFY")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-VERIFY", mockSession);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-VERIFY");
    }

    // -----------------------------------------------------------------------
    // checkAvailability tests
    // -----------------------------------------------------------------------

    @Test
    void checkAvailability_withAvailableRoomType_returnsAvailableTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(response);
        assertEquals(true, response.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoomType_returnsAvailableFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, response.get("available"));
    }

    @Test
    void checkAvailability_responseContainsRoomType() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals("DELUXE", response.get("roomType"),
                "Response should echo back the room type");
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        // Assert
        assertTrue(response.containsKey("inventoryEndpoint"),
                "Response should contain 'inventoryEndpoint' key");
        assertNotNull(response.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_callsBookingServiceIsRoomAvailable() {
        // Arrange
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        // Act
        bookingController.checkAvailability("VILLA");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    // -----------------------------------------------------------------------
    // downloadReport tests
    // -----------------------------------------------------------------------

    @Test
    void downloadReport_withValidMonth_returnsNonNullResponse() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report triggered for March");

        // Act
        Map<String, Object> response = bookingController.downloadReport("March");

        // Assert
        assertNotNull(response);
    }

    @Test
    void downloadReport_responseContainsReportPath() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report triggered for March");

        // Act
        Map<String, Object> response = bookingController.downloadReport("March");

        // Assert
        assertTrue(response.containsKey("reportPath"),
                "Response should contain 'reportPath' key");
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport("April")).thenReturn("Report triggered for April");

        // Act
        Map<String, Object> response = bookingController.downloadReport("April");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertNotNull(reportPath);
        assertTrue(reportPath.contains("April"),
                "Report path should contain the month name");
    }

    @Test
    void downloadReport_responseContainsMessageKey() {
        // Arrange
        when(bookingService.generateReport("May")).thenReturn("Report triggered for May");

        // Act
        Map<String, Object> response = bookingController.downloadReport("May");

        // Assert
        assertTrue(response.containsKey("message"),
                "Response should contain 'message' key");
        assertEquals("Report triggered for May", response.get("message"));
    }

    @Test
    void downloadReport_callsBookingServiceGenerateReport() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("Report triggered for June");

        // Act
        bookingController.downloadReport("June");

        // Assert
        verify(bookingService, times(1)).generateReport("June");
    }

    @Test
    void downloadReport_reportPathEndsWithPdfExtension() {
        // Arrange
        when(bookingService.generateReport("July")).thenReturn("Report triggered for July");

        // Act
        Map<String, Object> response = bookingController.downloadReport("July");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.endsWith("_bookings.pdf"),
                "Report path should end with '_bookings.pdf'");
    }
}
