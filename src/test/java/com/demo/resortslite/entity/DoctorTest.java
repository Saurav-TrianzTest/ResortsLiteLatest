package com.demo.resortslite.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DoctorTest {

    private Doctor doctor;

    @BeforeEach
    void setUp() {
        doctor = new Doctor();
    }

    // -----------------------------------------------------------------------
    // Default constructor test
    // -----------------------------------------------------------------------

    @Test
    void defaultConstructor_createsNonNullInstance() {
        // Act
        Doctor d = new Doctor();

        // Assert
        assertNotNull(d);
    }

    @Test
    void defaultConstructor_allFieldsAreNullByDefault() {
        // Act
        Doctor d = new Doctor();

        // Assert
        assertNull(d.getDocId(), "docId should be null by default");
        assertNull(d.getDocName(), "docName should be null by default");
        assertNull(d.getSpecialization(), "specialization should be null by default");
        assertNull(d.getIncome(), "income should be null by default");
    }

    // -----------------------------------------------------------------------
    // setDocId / getDocId tests
    // -----------------------------------------------------------------------

    @Test
    void setDocId_withValidId_getDocIdReturnsCorrectValue() {
        // Arrange
        Integer expectedId = 101;

        // Act
        doctor.setDocId(expectedId);

        // Assert
        assertEquals(expectedId, doctor.getDocId());
    }

    @Test
    void setDocId_withZero_getDocIdReturnsZero() {
        // Act
        doctor.setDocId(0);

        // Assert
        assertEquals(0, doctor.getDocId());
    }

    @Test
    void setDocId_withNegativeValue_getDocIdReturnsNegativeValue() {
        // Act
        doctor.setDocId(-1);

        // Assert
        assertEquals(-1, doctor.getDocId());
    }

    @Test
    void setDocId_withNull_getDocIdReturnsNull() {
        // Arrange
        doctor.setDocId(203);

        // Act
        doctor.setDocId(null);

        // Assert
        assertNull(doctor.getDocId());
    }

    @Test
    void setDocId_withLargeValue_getDocIdReturnsCorrectValue() {
        // Act
        doctor.setDocId(Integer.MAX_VALUE);

        // Assert
        assertEquals(Integer.MAX_VALUE, doctor.getDocId());
    }

    // -----------------------------------------------------------------------
    // setDocName / getDocName tests
    // -----------------------------------------------------------------------

    @Test
    void setDocName_withValidName_getDocNameReturnsCorrectValue() {
        // Act
        doctor.setDocName("Dr. John Smith");

        // Assert
        assertEquals("Dr. John Smith", doctor.getDocName());
    }

    @Test
    void setDocName_withEmptyString_getDocNameReturnsEmptyString() {
        // Act
        doctor.setDocName("");

        // Assert
        assertEquals("", doctor.getDocName());
    }

    @Test
    void setDocName_withNull_getDocNameReturnsNull() {
        // Arrange
        doctor.setDocName("Some Name");

        // Act
        doctor.setDocName(null);

        // Assert
        assertNull(doctor.getDocName());
    }

    @Test
    void setDocName_withMaxLengthName_getDocNameReturnsCorrectValue() {
        // 25 characters (column length = 25)
        String maxName = "ABCDEFGHIJKLMNOPQRSTUVWXY";

        // Act
        doctor.setDocName(maxName);

        // Assert
        assertEquals(maxName, doctor.getDocName());
        assertEquals(25, doctor.getDocName().length());
    }

    @Test
    void setDocName_withSpecialCharacters_getDocNameReturnsCorrectValue() {
        // Act
        doctor.setDocName("Dr. O'Brien-Smith");

        // Assert
        assertEquals("Dr. O'Brien-Smith", doctor.getDocName());
    }

    // -----------------------------------------------------------------------
    // setSpecialization / getSpecialization tests
    // -----------------------------------------------------------------------

    @Test
    void setSpecialization_withValidValue_getSpecializationReturnsCorrectValue() {
        // Act
        doctor.setSpecialization("Cardiology");

        // Assert
        assertEquals("Cardiology", doctor.getSpecialization());
    }

    @Test
    void setSpecialization_withEmptyString_getSpecializationReturnsEmptyString() {
        // Act
        doctor.setSpecialization("");

        // Assert
        assertEquals("", doctor.getSpecialization());
    }

    @Test
    void setSpecialization_withNull_getSpecializationReturnsNull() {
        // Arrange
        doctor.setSpecialization("Neurology");

        // Act
        doctor.setSpecialization(null);

        // Assert
        assertNull(doctor.getSpecialization());
    }

    @Test
    void setSpecialization_withMaxLengthValue_getSpecializationReturnsCorrectValue() {
        // 20 characters (column length = 20)
        String maxSpec = "ABCDEFGHIJKLMNOPQRST";

        // Act
        doctor.setSpecialization(maxSpec);

        // Assert
        assertEquals(maxSpec, doctor.getSpecialization());
        assertEquals(20, doctor.getSpecialization().length());
    }

    @Test
    void setSpecialization_withDifferentValues_updatesCorrectly() {
        // Act
        doctor.setSpecialization("Cardiology");
        doctor.setSpecialization("Neurology");

        // Assert
        assertEquals("Neurology", doctor.getSpecialization(),
                "Specialization should be updated to the latest value");
    }

    // -----------------------------------------------------------------------
    // setIncome / getIncome tests
    // -----------------------------------------------------------------------

    @Test
    void setIncome_withValidDouble_getIncomeReturnsCorrectValue() {
        // Act
        doctor.setIncome(75000.50);

        // Assert
        assertEquals(75000.50, doctor.getIncome(), 0.001);
    }

    @Test
    void setIncome_withZero_getIncomeReturnsZero() {
        // Act
        doctor.setIncome(0.0);

        // Assert
        assertEquals(0.0, doctor.getIncome(), 0.001);
    }

    @Test
    void setIncome_withNegativeValue_getIncomeReturnsNegativeValue() {
        // Act
        doctor.setIncome(-1000.0);

        // Assert
        assertEquals(-1000.0, doctor.getIncome(), 0.001);
    }

    @Test
    void setIncome_withNull_getIncomeReturnsNull() {
        // Arrange
        doctor.setIncome(50000.0);

        // Act
        doctor.setIncome(null);

        // Assert
        assertNull(doctor.getIncome());
    }

    @Test
    void setIncome_withLargeValue_getIncomeReturnsCorrectValue() {
        // Act
        doctor.setIncome(Double.MAX_VALUE);

        // Assert
        assertEquals(Double.MAX_VALUE, doctor.getIncome(), 0.001);
    }

    @Test
    void setIncome_withDecimalPrecision_getIncomeReturnsCorrectValue() {
        // Act
        doctor.setIncome(123456.789);

        // Assert
        assertEquals(123456.789, doctor.getIncome(), 0.0001);
    }

    // -----------------------------------------------------------------------
    // Combined setter/getter tests
    // -----------------------------------------------------------------------

    @Test
    void doctor_setAllFields_getAllFieldsReturnCorrectValues() {
        // Arrange
        Integer expectedId = 203;
        String expectedName = "Dr. Alice Brown";
        String expectedSpec = "Orthopedics";
        Double expectedIncome = 95000.00;

        // Act
        doctor.setDocId(expectedId);
        doctor.setDocName(expectedName);
        doctor.setSpecialization(expectedSpec);
        doctor.setIncome(expectedIncome);

        // Assert
        assertEquals(expectedId, doctor.getDocId());
        assertEquals(expectedName, doctor.getDocName());
        assertEquals(expectedSpec, doctor.getSpecialization());
        assertEquals(expectedIncome, doctor.getIncome(), 0.001);
    }

    @Test
    void doctor_twoInstances_areIndependent() {
        // Arrange
        Doctor doctor1 = new Doctor();
        Doctor doctor2 = new Doctor();

        // Act
        doctor1.setDocId(1);
        doctor1.setDocName("Dr. First");
        doctor2.setDocId(2);
        doctor2.setDocName("Dr. Second");

        // Assert
        assertNotEquals(doctor1.getDocId(), doctor2.getDocId());
        assertNotEquals(doctor1.getDocName(), doctor2.getDocName());
    }

    @Test
    void doctor_updateDocName_doesNotAffectOtherFields() {
        // Arrange
        doctor.setDocId(100);
        doctor.setDocName("Original Name");
        doctor.setSpecialization("Cardiology");
        doctor.setIncome(80000.0);

        // Act
        doctor.setDocName("Updated Name");

        // Assert
        assertEquals(100, doctor.getDocId());
        assertEquals("Updated Name", doctor.getDocName());
        assertEquals("Cardiology", doctor.getSpecialization());
        assertEquals(80000.0, doctor.getIncome(), 0.001);
    }
}
