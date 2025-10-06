package gov.irs.form8582.model;

import javax.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.math.BigDecimal;

@Entity
@Table(name = "passive_activities")
@Data
@NoArgsConstructor
public class PassiveActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_name", nullable = false)
    private String activityName;

    @Column(name = "activity_type")
    private String activityType; // Rental Real Estate, Trade/Business, etc.

    @Column(name = "current_year_income")
    private BigDecimal currentYearIncome;

    @Column(name = "current_year_loss")
    private BigDecimal currentYearLoss;

    @Column(name = "prior_year_unallowed_loss")
    private BigDecimal priorYearUnallowedLoss;

    @Column(name = "active_participation")
    private Boolean activeParticipation = false;

    @Column(name = "material_participation")
    private Boolean materialParticipation = false;

    @Column(name = "tax_year")
    private Integer taxYear;

    @Column(name = "form_schedule")
    private String formSchedule; // Schedule E, Schedule C, etc.

    @Column(name = "is_ptp")
    private Boolean isPubliclyTradedPartnership = false;
}