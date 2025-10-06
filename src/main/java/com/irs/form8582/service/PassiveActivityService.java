package com.irs.form8582.service;

import com.irs.form8582.model.PassiveActivity;
import com.irs.form8582.repository.PassiveActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PassiveActivityService {

    private final PassiveActivityRepository repository;

    public PassiveActivity saveActivity(PassiveActivity activity) {
        return repository.save(activity);
    }

    public List<PassiveActivity> getActivitiesByYear(Integer taxYear) {
        return repository.findByTaxYear(taxYear);
    }

    public BigDecimal calculateOverallGan(PassiveActivity activity) {
        BigDecimal totalIncome = activity.getCurrentYearIncome() != null ? activity.getCurrentYearIncome() : BigDecimal.ZERO;
        BigDecimal totalLoss = activity.getCurrentYearLoss() != null ? activity.getCurrentYearLoss() : BigDecimal.ZERO;
        BigDecimal priorLoss = activity.getPriorYearUnallowedLoss() != null ? activity.getPriorYearUnallowedLoss() : BigDecimal.ZERO;
        return totalIncome.subtract(totalLoss).subtract(priorLoss);
    }
}