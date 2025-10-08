package com.irs.form8582.model;

import javax.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "form_8582_data")
@Data
@NoArgsConstructor
@AllArgsConstructor
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

    @Column(name = "total_passive_income")
    private BigDecimal totalPassiveIncome;

    @Column(name = "total_passive_loss")
    private BigDecimal totalPassiveLoss;

    @Column(name = "special_allowance")
    private BigDecimal specialAllowance;

    @Column(name = "allowed_loss")
    private BigDecimal allowedLoss;

    @Column(name = "unallowed_loss")
    private BigDecimal unallowedLoss;

    @Column(name = "filing_status")
    private String filingStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}