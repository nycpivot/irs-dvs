package gov.irs.form8582.service;

import gov.irs.form8582.model.Form8582Data;
import gov.irs.form8582.model.PassiveActivity;
import gov.irs.form8582.repository.Form8582DataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class Form8582CalculationService {

    private final Form8582DataRepository form8582DataRepository;
    private final PassiveActivityLossService passiveActivityLossService;

    /**
     * Calculate Part I - 2024 Passive Activity Loss
     */
    public BigDecimal calculatePartI(List<PassiveActivity> activities) {
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalLosses = BigDecimal.ZERO;

        for (PassiveActivity activity : activities) {
            if (activity.getCurrentYearIncome() != null) {
                totalIncome = totalIncome.add(activity.getCurrentYearIncome());
            }
            if (activity.getCurrentYearLoss() != null) {
                totalLosses = totalLosses.add(activity.getCurrentYearLoss());
            }
            if (activity.getPriorYearUnallowedLoss() != null) {
                totalLosses = totalLosses.add(activity.getPriorYearUnallowedLoss());
            }
        }

        return totalIncome.subtract(totalLosses);
    }

    /**
     * Save Form 8582 data
     */
    public Form8582Data saveFormData(Form8582Data formData) {
        return form8582DataRepository.save(formData);
    }
}