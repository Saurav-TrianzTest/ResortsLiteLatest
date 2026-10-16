package com.demo.resortslite.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Doctor} entity – covers all getters, setters, and
 * default-constructor behaviour.
 */
class DoctorTest {

    private Doctor doctor;

    @BeforeEach
    void setUp() {
        doctor = new Doctor();
    }

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    @Test
    void defaultConstructor_createsNonNullInstance() {
        // Assert
        assertNotNull(doctor, "Default constructor should create a non-null Doctor instance");
    }

    @Test
    void defaultConstructor_docIdIsNullByDefault() {
        // Assert
        assertNull(doctor.getDocId(), "docId should be null after default construction");
    }

    @Test
    void defaultConstructor_docNameIsNullByDefault() {
        // Assert
        assertNull(doctor.getDocName(), "docName should be null after default construction");
    }

    @Test
    void defaultConstructor_specializationIsNullByDefault() {
        // Assert
        assertNull(doctor.getSpecialization(),
                "specialization should be null after default construction");
    }

    @Test
    void defaultConstructor_incomeIsNullByDefault() {
        // Assert
        assertNull(doctor.getIncome(), "income should be null after default construction");
    }

    // -----------------------------------------------------------------------
    // setDocId / getDocId
    // -----------------------------------------------------------------------

    @Test
    void setDocId_storesValue() {
        // Act
        doctor.setDocId(101);

        // Assert
        assertEquals(101, doctor.getDocId());
    }

    @Test
    void setDocId_updatesExistingValue() {
        // Arrange
        doctor.setDocId(1);

        // Act
        doctor.setDocId(999);

        // Assert
        assertEquals(999, doctor.getDocId());
    }

    @Test
    void setDocId_acceptsNull() {
        // Arrange
        doctor.setDocId(42);

        // Act
        doctor.setDocId(null);

        // Assert
        assertNull(doctor.getDocId());
    }

    @Test
    void setDocId_acceptsZero() {
        // Act
        doctor.setDocId(0);

        // Assert
        assertEquals(0, doctor.getDocId());
    }

    @Test
    void setDocId_acceptsNegativeValue() {
        // Act
        doctor.setDocId(-5);

        // Assert
        assertEquals(-5, doctor.getDocId());
    }

    // -----------------------------------------------------------------------
    // setDocName / getDocName
    // -----------------------------------------------------------------------

    @Test
    void setDocName_storesValue() {
        // Act
        doctor.setDocName("Dr. Smith");

        // Assert
        assertEquals("Dr. Smith", doctor.getDocName());
    }

    @Test
    void setDocName_updatesExistingValue() {
        // Arrange
        doctor.setDocName("Dr. Old");

        // Act
        doctor.setDocName("Dr. New");

        // Assert
        assertEquals("Dr. New", doctor.getDocName());
    }

    @Test
    void setDocName_acceptsNull() {
        // Arrange
        doctor.setDocName("Dr. Someone");

        // Act
        doctor.setDocName(null);

        // Assert
        assertNull(doctor.getDocName());
    }

    @Test
    void setDocName_acceptsEmptyString() {
        // Act
        doctor.setDocName("");

        // Assert
        assertEquals("", doctor.getDocName());
    }

    @Test
    void setDocName_acceptsMaxLengthName() {
        // 25-character string (column length = 25)
        String name = "ABCDEFGHIJKLMNOPQRSTUVWXY";
        doctor.setDocName(name);
        assertEquals(name, doctor.getDocName());
    }

    // -----------------------------------------------------------------------
    // setSpecialization / getSpecialization
    // -----------------------------------------------------------------------

    @Test
    void setSpecialization_storesValue() {
        // Act
        doctor.setSpecialization("Cardiology");

        // Assert
        assertEquals("Cardiology", doctor.getSpecialization());
    }

    @Test
    void setSpecialization_updatesExistingValue() {
        // Arrange
        doctor.setSpecialization("Neurology");

        // Act
        doctor.setSpecialization("Orthopedics");

        // Assert
        assertEquals("Orthopedics", doctor.getSpecialization());
    }

    @Test
    void setSpecialization_acceptsNull() {
        // Arrange
        doctor.setSpecialization("Dermatology");

        // Act
        doctor.setSpecialization(null);

        // Assert
        assertNull(doctor.getSpecialization());
    }

    @Test
    void setSpecialization_acceptsEmptyString() {
        // Act
        doctor.setSpecialization("");

        // Assert
        assertEquals("", doctor.getSpecialization());
    }

    @Test
    void setSpecialization_acceptsMaxLengthValue() {
        // 20-character string (column length = 20)
        String spec = "ABCDEFGHIJKLMNOPQRST";
        doctor.setSpecialization(spec);
        assertEquals(spec, doctor.getSpecialization());
    }

    // -----------------------------------------------------------------------
    // setIncome / getIncome
    // -----------------------------------------------------------------------

    @Test
    void setIncome_storesValue() {
        // Act
        doctor.setIncome(75000.50);

        // Assert
        assertEquals(75000.50, doctor.getIncome(), 0.001);
    }

    @Test
    void setIncome_updatesExistingValue() {
        // Arrange
        doctor.setIncome(50000.0);

        // Act
        doctor.setIncome(90000.0);

        // Assert
        assertEquals(90000.0, doctor.getIncome(), 0.001);
    }

    @Test
    void setIncome_acceptsNull() {
        // Arrange
        doctor.setIncome(100000.0);

        // Act
        doctor.setIncome(null);

        // Assert
        assertNull(doctor.getIncome());
    }

    @Test
    void setIncome_acceptsZero() {
        // Act
        doctor.setIncome(0.0);

        // Assert
        assertEquals(0.0, doctor.getIncome(), 0.001);
    }

    @Test
    void setIncome_acceptsNegativeValue() {
        // Act
        doctor.setIncome(-1000.0);

        // Assert
        assertEquals(-1000.0, doctor.getIncome(), 0.001);
    }

    @Test
    void setIncome_acceptsLargeValue() {
        // Act
        doctor.setIncome(Double.MAX_VALUE);

        // Assert
        assertEquals(Double.MAX_VALUE, doctor.getIncome(), 0.001);
    }

    // -----------------------------------------------------------------------
    // Combined field tests
    // -----------------------------------------------------------------------

    @Test
    void allFields_setAndGetCorrectly() {
        // Arrange / Act
        doctor.setDocId(203);
        doctor.setDocName("Dr. Johnson");
        doctor.setSpecialization("Pediatrics");
        doctor.setIncome(120000.00);

        // Assert
        assertAll("All Doctor fields should be set correctly",
                () -> assertEquals(203, doctor.getDocId()),
                () -> assertEquals("Dr. Johnson", doctor.getDocName()),
                () -> assertEquals("Pediatrics", doctor.getSpecialization()),
                () -> assertEquals(120000.00, doctor.getIncome(), 0.001)
        );
    }

    @Test
    void twoDistinctDoctors_haveIndependentState() {
        // Arrange
        Doctor doctor1 = new Doctor();
        Doctor doctor2 = new Doctor();

        // Act
        doctor1.setDocId(1);
        doctor1.setDocName("Dr. Alpha");
        doctor2.setDocId(2);
        doctor2.setDocName("Dr. Beta");

        // Assert
        assertNotEquals(doctor1.getDocId(), doctor2.getDocId());
        assertNotEquals(doctor1.getDocName(), doctor2.getDocName());
    }
}
