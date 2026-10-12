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
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> fakeBooking = new HashMap<>();
        fakeBooking.put("bookingId", "BK-ABCD1234");
        fakeBooking.put("guestName", "Alice");
        fakeBooking.put("roomType", "SUITE");
        fakeBooking.put("checkIn", "2024-06-01");
        fakeBooking.put("checkOut", "2024-06-05");
        fakeBooking.put("confirmationCode", "abc123");

        when(bookingService.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05"))
                .thenReturn(fakeBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", mockSession);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
        assertEquals(fakeBooking, response.get("booking"));
    }

    @Test
    void createBooking_storesBookingInSession() {
        // Arrange
        Map<String, Object> fakeBooking = new HashMap<>();
        fakeBooking.put("bookingId", "BK-XYZ99");
        fakeBooking.put("guestName", "Bob");

        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(fakeBooking);

        // Act
        bookingController.createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-03", mockSession);

        // Assert
        assertEquals(fakeBooking, mockSession.getAttribute("lastBooking"));
        assertEquals("Bob", mockSession.getAttribute("guestName"));
    }

    @Test
    void createBooking_callsBookingServiceWithCorrectParams() {
        // Arrange
        Map<String, Object> fakeBooking = new HashMap<>();
        fakeBooking.put("bookingId", "BK-TEST01");

        when(bookingService.createBooking("Carol", "VILLA", "2024-08-10", "2024-08-17"))
                .thenReturn(fakeBooking);

        // Act
        bookingController.createBooking("Carol", "VILLA", "2024-08-10", "2024-08-17", mockSession);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Carol", "VILLA", "2024-08-10", "2024-08-17");
    }

    @Test
    void createBooking_responseContainsBookingObject() {
        // Arrange
        Map<String, Object> fakeBooking = new HashMap<>();
        fakeBooking.put("bookingId", "BK-RESP01");
        fakeBooking.put("roomType", "STANDARD");

        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(fakeBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Dave", "STANDARD", "2024-09-01", "2024-09-02", mockSession);

        // Assert
        assertTrue(response.containsKey("booking"));
        assertTrue(response.containsKey("status"));
    }

    @Test
    void createBooking_withEmptyGuestName_stillCallsService() {
        // Arrange
        Map<String, Object> fakeBooking = new HashMap<>();
        fakeBooking.put("bookingId", "BK-EMPTY1");

        when(bookingService.createBooking("", "STANDARD", "2024-01-01", "2024-01-02"))
                .thenReturn(fakeBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "", "STANDARD", "2024-01-01", "2024-01-02", mockSession);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    // -----------------------------------------------------------------------
    // getBookingStatus tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_withValidBookingId_returnsResult() {
        // Arrange
        Map<String, Object> bookingDetails = new HashMap<>();
        bookingDetails.put("bookingId", "BK-STATUS1");
        bookingDetails.put("guestName", "Eve");

        when(bookingService.getBookingById("BK-STATUS1")).thenReturn(bookingDetails);
        mockSession.setAttribute("guestName", "Eve");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS1", mockSession);

        // Assert
        assertNotNull(result);
        assertEquals("BK-STATUS1", result.get("bookingId"));
        assertEquals("Eve", result.get("sessionGuest"));
        assertEquals(bookingDetails, result.get("details"));
    }

    @Test
    void getBookingStatus_withNoSessionGuest_returnsNullSessionGuest() {
        // Arrange
        Map<String, Object> bookingDetails = new HashMap<>();
        bookingDetails.put("bookingId", "BK-NOGUEST");

        when(bookingService.getBookingById("BK-NOGUEST")).thenReturn(bookingDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-NOGUEST", mockSession);

        // Assert
        assertNotNull(result);
        assertNull(result.get("sessionGuest"));
        assertEquals("BK-NOGUEST", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_callsBookingServiceGetById() {
        // Arrange
        when(bookingService.getBookingById("BK-VERIFY")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-VERIFY", mockSession);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-VERIFY");
    }

    @Test
    void getBookingStatus_responseContainsRequiredKeys() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-KEYS", mockSession);

        // Assert
        assertTrue(result.containsKey("bookingId"));
        assertTrue(result.containsKey("sessionGuest"));
        assertTrue(result.containsKey("details"));
    }

    // -----------------------------------------------------------------------
    // checkAvailability tests
    // -----------------------------------------------------------------------

    @Test
    void checkAvailability_withAvailableRoom_returnsTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(response);
        assertEquals("SUITE", response.get("roomType"));
        assertEquals(true, response.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertNotNull(response);
        assertEquals(false, response.get("available"));
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        // Assert
        assertNotNull(response.get("inventoryEndpoint"));
        assertTrue(response.get("inventoryEndpoint").toString().contains("inventory-service"));
    }

    @Test
    void checkAvailability_callsIsRoomAvailableOnService() {
        // Arrange
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        // Act
        bookingController.checkAvailability("STANDARD");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("STANDARD");
    }

    // -----------------------------------------------------------------------
    // downloadReport tests
    // -----------------------------------------------------------------------

    @Test
    void downloadReport_withValidMonth_returnsReportPath() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        // Act
        Map<String, Object> response = bookingController.downloadReport("March");

        // Assert
        assertNotNull(response);
        assertNotNull(response.get("reportPath"));
        assertTrue(response.get("reportPath").toString().contains("March"));
    }

    @Test
    void downloadReport_responseContainsMessage() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("Report for June");

        // Act
        Map<String, Object> response = bookingController.downloadReport("June");

        // Assert
        assertEquals("Report for June", response.get("message"));
    }

    @Test
    void downloadReport_reportPathContainsPdfExtension() {
        // Arrange
        when(bookingService.generateReport("December")).thenReturn("Report for December");

        // Act
        Map<String, Object> response = bookingController.downloadReport("December");

        // Assert
        String path = (String) response.get("reportPath");
        assertTrue(path.endsWith(".pdf"));
    }

    @Test
    void downloadReport_callsGenerateReportOnService() {
        // Arrange
        when(bookingService.generateReport("January")).thenReturn("ok");

        // Act
        bookingController.downloadReport("January");

        // Assert
        verify(bookingService, times(1)).generateReport("January");
    }
}
