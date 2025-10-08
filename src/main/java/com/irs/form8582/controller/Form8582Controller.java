package com.irs.form8582.controller;

import com.irs.form8582.model.Form8582Data;
import com.irs.form8582.service.PassiveActivityLossService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/form8582")
@RequiredArgsConstructor
public class Form8582Controller {

    private final PassiveActivityLossService palService;

    @PostMapping("/calculate")
    public ResponseEntity<Form8582Data> calculatePassiveLoss(
            @RequestParam String taxpayerId,
            @RequestParam Integer taxYear,
            @RequestParam BigDecimal modifiedAgi,
            @RequestParam String filingStatus) {
        
        Form8582Data result = palService.calculatePassiveActivityLoss(
                taxpayerId, taxYear, modifiedAgi, filingStatus);
        
        return ResponseEntity.ok(result);
    }
}