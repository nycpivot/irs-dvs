package com.irs.form8582.model;

import javax.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "form_8582_data")
public class Form8582Data {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "taxpayer_id", nullable = false)
    private String taxpayerId;

    @Column(name = "tax_year", nullable = false)
    private Integer taxYear;

    @Column(name = "modified_agi")
    private BigDecimal modifiedAgi;

    @Column(name = "special_allowance")
    private BigDecimal specialAllowance;

    @Column(name = "total_losses_allowed")
    private BigDecimal totalLossesAllowed;

    @Column(name = "filing_status")
    private String filingStatus; // SINGLE, MARRIED_JOINT, MARRIED_SEPARATE

    @OneToMany(mappedBy = "form8582Data", cascade = CascadeType.ALL)
    private List<PassiveActivity> activities;

    @Column(name = "created_date")
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdDate;
}