package com.irs.form8582.controller;

import com.irs.form8582.model.Form8582Data;
import com.irs.form8582.model.PassiveActivity;
import com.irs.form8582.service.Form8582CalculationService;
import com.irs.form8582.service.PassiveActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/form8582")
@RequiredArgsConstructor
public class Form8582Controller {

    private final PassiveActivityService activityService;
    private final Form8582CalculationService calculationService;

    @PostMapping("/activities")
    public ResponseEntity<PassiveActivity> createActivity(@RequestBody PassiveActivity activity) {
        PassiveActivity saved = activityService.saveActivity(activity);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/activities/{year}")
    public ResponseEntity<List<PassiveActivity>> getActivitiesByYear(@PathVariable Integer year) {
        List<PassiveActivity> activities = activityService.getActivitiesByYear(year);
        return ResponseEntity.ok(activities);
    }

    @PostMapping("/calculate")
    public ResponseEntity<Map<String, Object>> calculateForm(@RequestBody Form8582Data formData) {
        Map<String, Object> result = new HashMap<>();
        
        BigDecimal specialAllowance = calculationService.calculateSpecialAllowance(
            formData.getModifiedAgi(), 
            formData.getFilingStatus()
        );
        
        BigDecimal totalLossesAllowed = calculationService.calculateTotalLossesAllowed(
            formData.getActivities(), 
            specialAllowance
        );
        
        result.put("specialAllowance", specialAllowance);
        result.put("totalLossesAllowed", totalLossesAllowed);
        result.put("taxYear", formData.getTaxYear());
        
        return ResponseEntity.ok(result);
    }
}