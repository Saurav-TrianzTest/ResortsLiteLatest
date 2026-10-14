package com.demo.resortslite;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private HttpSession httpSession;

    @InjectMocks
    private BookingController bookingController;

    private Map<String, Object> sampleBooking;

    @BeforeEach
    void setUp() {
        sampleBooking = new HashMap<>();
        sampleBooking.put("bookingId", "BK-ABCD1234");
        sampleBooking.put("guestName", "Alice");
        sampleBooking.put("roomType", "SUITE");
        sampleBooking.put("checkIn", "2024-06-01");
        sampleBooking.put("checkOut", "2024-06-05");
        sampleBooking.put("confirmationCode", "abc123def456");
    }

    // -----------------------------------------------------------------------
    // createBooking tests
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsResponseWithStatusConfirmed() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", httpSession);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidParams_responseContainsBookingKey() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", httpSession);

        // Assert
        assertTrue(response.containsKey("booking"));
        assertNotNull(response.get("booking"));
    }

    @Test
    void createBooking_callsBookingServiceCreateBooking() {
        // Arrange
        when(bookingService.createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-03"))
                .thenReturn(sampleBooking);

        // Act
        bookingController.createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-03", httpSession);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-03");
    }

    @Test
    void createBooking_setsLastBookingInSession() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", httpSession);

        // Assert
        verify(httpSession, times(1)).setAttribute(eq("lastBooking"), any());
    }

    @Test
    void createBooking_setsGuestNameInSession() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", httpSession);

        // Assert
        verify(httpSession, times(1)).setAttribute("guestName", "Alice");
    }

    @Test
    void createBooking_bookingAddedToCache_responseContainsBookingData() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", httpSession);

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> bookingInResponse = (Map<String, Object>) response.get("booking");
        assertNotNull(bookingInResponse);
        assertEquals("BK-ABCD1234", bookingInResponse.get("bookingId"));
    }

    // -----------------------------------------------------------------------
    // getBookingStatus tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_withValidBookingId_returnsMapWithBookingIdKey() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn("Alice");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(sampleBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", httpSession);

        // Assert
        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_returnsSessionGuestName() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn("Bob");
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-XYZ", httpSession);

        // Assert
        assertEquals("Bob", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_whenNoSessionGuest_sessionGuestIsNull() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn(null);
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-XYZ", httpSession);

        // Assert
        assertNull(result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_containsDetailsKey() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn("Carol");
        when(bookingService.getBookingById("BK-TEST")).thenReturn(sampleBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-TEST", httpSession);

        // Assert
        assertTrue(result.containsKey("details"));
    }

    @Test
    void getBookingStatus_callsBookingServiceGetBookingById() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn("Dave");
        when(bookingService.getBookingById("BK-FINDME")).thenReturn(sampleBooking);

        // Act
        bookingController.getBookingStatus("BK-FINDME", httpSession);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-FINDME");
    }

    // -----------------------------------------------------------------------
    // checkAvailability tests
    // -----------------------------------------------------------------------

    @Test
    void checkAvailability_withValidRoomType_returnsMapWithRoomTypeKey() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(result);
        assertEquals("SUITE", result.get("roomType"));
    }

    @Test
    void checkAvailability_whenRoomAvailable_returnsTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals(true, result.get("available"));
    }

    @Test
    void checkAvailability_whenRoomNotAvailable_returnsFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, result.get("available"));
    }

    @Test
    void checkAvailability_containsInventoryEndpointKey() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert
        assertTrue(result.containsKey("inventoryEndpoint"));
        assertNotNull(result.get("inventoryEndpoint"));
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
    void downloadReport_withValidMonth_returnsMapWithReportPathKey() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        // Act
        Map<String, Object> result = bookingController.downloadReport("March");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("reportPath"));
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("Report generated for June");

        // Act
        Map<String, Object> result = bookingController.downloadReport("June");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertNotNull(reportPath);
        assertTrue(reportPath.contains("June"), "Report path should contain the month");
    }

    @Test
    void downloadReport_containsMessageKey() {
        // Arrange
        when(bookingService.generateReport("December")).thenReturn("Report for December");

        // Act
        Map<String, Object> result = bookingController.downloadReport("December");

        // Assert
        assertTrue(result.containsKey("message"));
    }

    @Test
    void downloadReport_messageMatchesServiceResponse() {
        // Arrange
        when(bookingService.generateReport("July")).thenReturn("Report for July via API");

        // Act
        Map<String, Object> result = bookingController.downloadReport("July");

        // Assert
        assertEquals("Report for July via API", result.get("message"));
    }

    @Test
    void downloadReport_callsBookingServiceGenerateReport() {
        // Arrange
        when(bookingService.generateReport("August")).thenReturn("Report for August");

        // Act
        bookingController.downloadReport("August");

        // Assert
        verify(bookingService, times(1)).generateReport("August");
    }

    @Test
    void downloadReport_reportPathEndsWithPdf() {
        // Arrange
        when(bookingService.generateReport("September")).thenReturn("Report for September");

        // Act
        Map<String, Object> result = bookingController.downloadReport("September");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertNotNull(reportPath);
        assertTrue(reportPath.endsWith(".pdf"), "Report path should end with .pdf");
    }
}
