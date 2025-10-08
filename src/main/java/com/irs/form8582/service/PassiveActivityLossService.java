package com.irs.form8582.service;

import com.irs.form8582.model.PassiveActivity;
import com.irs.form8582.model.Form8582Data;
import com.irs.form8582.repository.PassiveActivityRepository;
import com.irs.form8582.repository.Form8582DataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PassiveActivityLossService {

    private final PassiveActivityRepository activityRepository;
    private final Form8582DataRepository formDataRepository;

    public Form8582Data calculatePassiveActivityLoss(String taxpayerId, Integer taxYear, BigDecimal modifiedAgi, String filingStatus) {
        List<PassiveActivity> activities = activityRepository.findByTaxYear(taxYear);

        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;

        for (PassiveActivity activity : activities) {
            if (activity.getCurrentYearIncome() != null) {
                totalIncome = totalIncome.add(activity.getCurrentYearIncome());
            }
            if (activity.getCurrentYearLoss() != null) {
                totalLoss = totalLoss.add(activity.getCurrentYearLoss());
            }
            if (activity.getPriorYearUnallowedLoss() != null) {
                totalLoss = totalLoss.add(activity.getPriorYearUnallowedLoss());
            }
        }

        // Calculate special allowance for rental real estate
        BigDecimal specialAllowance = calculateSpecialAllowance(modifiedAgi, filingStatus);

        BigDecimal netLoss = totalLoss.subtract(totalIncome);
        BigDecimal allowedLoss = netLoss.min(specialAllowance);
        BigDecimal unallowedLoss = netLoss.subtract(allowedLoss);

        Form8582Data formData = new Form8582Data();
        formData.setTaxpayerId(taxpayerId);
        formData.setTaxYear(taxYear);
        formData.setModifiedAgi(modifiedAgi);
        formData.setTotalPassiveIncome(totalIncome);
        formData.setTotalPassiveLoss(totalLoss);
        formData.setSpecialAllowance(specialAllowance);
        formData.setAllowedLoss(allowedLoss);
        formData.setUnallowedLoss(unallowedLoss);
        formData.setFilingStatus(filingStatus);
        formData.setCreatedAt(LocalDateTime.now());
        formData.setUpdatedAt(LocalDateTime.now());

        return formDataRepository.save(formData);
    }

    private BigDecimal calculateSpecialAllowance(BigDecimal modifiedAgi, String filingStatus) {
        BigDecimal maxAllowance = new BigDecimal("25000");
        BigDecimal threshold = new BigDecimal("100000");
        BigDecimal phaseoutThreshold = new BigDecimal("150000");

        if ("MARRIED_FILING_SEPARATELY".equals(filingStatus)) {
            maxAllowance = new BigDecimal("12500");
            threshold = new BigDecimal("50000");
            phaseoutThreshold = new BigDecimal("75000");
        }

        if (modifiedAgi.compareTo(threshold) <= 0) {
            return maxAllowance;
        }

        if (modifiedAgi.compareTo(phaseoutThreshold) >= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal excess = phaseoutThreshold.subtract(modifiedAgi);
        return excess.multiply(new BigDecimal("0.5")).setScale(2, RoundingMode.HALF_UP);
    }
}