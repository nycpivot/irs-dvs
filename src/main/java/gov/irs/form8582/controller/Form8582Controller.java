package gov.irs.form8582.controller;

import gov.irs.form8582.model.Form8582Data;
import gov.irs.form8582.model.PassiveActivity;
import gov.irs.form8582.service.Form8582CalculationService;
import gov.irs.form8582.service.PassiveActivityLossService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/form8582")
@RequiredArgsConstructor
public class Form8582Controller {

    private final Form8582CalculationService calculationService;
    private final PassiveActivityLossService passiveActivityLossService;

    @PostMapping("/calculate")
    public ResponseEntity<?> calculatePassiveActivityLoss(@RequestBody Form8582Data formData) {
        try {
            BigDecimal totalPAL = calculationService.calculatePartI(formData.getPassiveActivities());
            BigDecimal specialAllowance = passiveActivityLossService.calculateSpecialAllowance(
                formData.getModifiedAdjustedGrossIncome(), 
                formData.getFilingStatus()
            );
            
            formData.setSpecialAllowanceAmount(specialAllowance);
            formData.setTotalLossesAllowed(specialAllowance.min(totalPAL.abs()));
            
            Form8582Data savedData = calculationService.saveFormData(formData);
            return ResponseEntity.ok(savedData);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error calculating passive activity loss: " + e.getMessage());
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Form 8582 Service is running");
    }
}