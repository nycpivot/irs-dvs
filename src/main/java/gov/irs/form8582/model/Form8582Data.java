package gov.irs.form8582.model;

import javax.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "form_8582_data")
@Data
@NoArgsConstructor
public class Form8582Data {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "taxpayer_id", nullable = false)
    private String taxpayerId;

    @Column(name = "tax_year", nullable = false)
    private Integer taxYear;

    @Column(name = "filing_status")
    private String filingStatus;

    @Column(name = "modified_agi")
    private BigDecimal modifiedAdjustedGrossIncome;

    @Column(name = "special_allowance_amount")
    private BigDecimal specialAllowanceAmount;

    @Column(name = "total_losses_allowed")
    private BigDecimal totalLossesAllowed;

    @Column(name = "created_date")
    private LocalDate createdDate;

    @OneToMany(mappedBy = "form8582Data", cascade = CascadeType.ALL)
    private List<PassiveActivity> passiveActivities;
}