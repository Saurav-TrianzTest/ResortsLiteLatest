package com.demo.resortslite.entity;

// MIGRATED: javax.persistence.* replaced with jakarta.persistence.* (Java 25 / Spring Boot 3.x).
// Spring Boot 3.x uses the Jakarta EE 10 namespace. The javax.persistence.* packages were
// removed from the JDK and are no longer available on a Jakarta-based runtime.
// All seven JPA annotations below now resolve from the jakarta.persistence module.
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "JPA_DOCTOR_INFO")
public class Doctor {

    @Column(name = "DOC_ID")
    @Id
    @SequenceGenerator(name = "gen1", sequenceName = "CNO_SEQ", initialValue = 203, allocationSize = 1)
    @GeneratedValue(generator = "gen1", strategy = GenerationType.SEQUENCE)
    private Integer docId;

    @Column(name = "DOC_NAME", length = 25)
    private String docName;

    @Column(name = "SPECIALIZATION", length = 20)
    private String specialization;

    @Column(name = "INCOME")
    private Double income;

    public Integer getDocId() {
        return docId;
    }

    public void setDocId(Integer docId) {
        this.docId = docId;
    }

    public String getDocName() {
        return docName;
    }

    public void setDocName(String docName) {
        this.docName = docName;
    }

    public String getSpecialization() {
        return specialization;
    }

    public void setSpecialization(String specialization) {
        this.specialization = specialization;
    }

    public Double getIncome() {
        return income;
    }

    public void setIncome(Double income) {
        this.income = income;
    }
}
