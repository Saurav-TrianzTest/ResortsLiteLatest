package com.demo.resortslite.entity;

// FIXED: JAVA8_TO_25_JAKARTA_NAMESPACE_MIGRATION — replaced all javax.persistence.*
// imports with jakarta.persistence.* (required by Spring Boot 3.x / Jakarta EE 10)
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "jpa_doctor_info")
public class Doctor {

    @Column(name = "doc_id")
    @Id
    @SequenceGenerator(name = "gen1", sequenceName = "cno_seq", initialValue = 203, allocationSize = 1)
    @GeneratedValue(generator = "gen1", strategy = GenerationType.SEQUENCE)
    private Integer docId;

    @Column(name = "doc_name", length = 25)
    private String docName;

    @Column(name = "specialization", length = 20)
    private String specialization;

    @Column(name = "income")
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
