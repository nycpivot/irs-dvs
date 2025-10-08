package com.irs.form8582.model;

import javax.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "passive_activities")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PassiveActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_name", nullable = false)
    private String activityName;

    @Column(name = "activity_type")
    private String activityType; // RENTAL_REAL_ESTATE, TRADE_BUSINESS, etc.

    @Column(name = "current_year_income")
    private BigDecimal currentYearIncome;

    @Column(name = "current_year_loss")
    private BigDecimal currentYearLoss;

    @Column(name = "prior_year_unallowed_loss")
    private BigDecimal priorYearUnallowedLoss;

    @Column(name = "active_participation")
    private Boolean activeParticipation;

    @Column(name = "material_participation")
    private Boolean materialParticipation;

    @Column(name = "tax_year")
    private Integer taxYear;

    @Column(name = "created_date")
    private LocalDate createdDate;
}