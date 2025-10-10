package com.tax.dvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validator for IRS Schedule F and Form 8582 matching.
 *
 * Business Rules:
 * 1. Match business names between Schedule F and Form 8582
 * 2. Match total business income/loss amounts
 * 3. When there is a loss, Schedule F Line 34 shows $0, but actual loss is on Form 8582
 * 4. Calculate loss: Line 9 (Gross Income) - Line 33 (Total Expenses)
 * 5. Only passive activities (materiallyParticipatedInd=false) should appear on Form 8582
 * 6. Passive losses are limited and carries forward to future years
 */
@Component
public class ScheduleF8582Validator {

    private static final String SCHEDULE_F_FORM = "ISS1040ScheduleF";
    private static final String FORM_8582 = "IRS8582";
    private static final String FORM_8995 = "IRS8995";

    /**
     * Validates the entire tax return payload for Schedule F and Form 8582 consistency.
     *
     * @param payload The full tax return JSON payload
     * @return ValidationResult containing status and errors
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = ValidationResult.builder()
                .valid(true)
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .build();

        try {
            // Extract forms from payload
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                result.getErrors().add("Payload missing 'body.forms'");
                result.setValid(false);
                return result;
            }

            JsonNode forms = body.get("forms");

            // Parse Schedule F forms
            List<ScheduleFData> scheduleFList = parseScheduleFForms(forms);

            // Parse Form 8582
            Form8582Data form8582 = parseForm8582(forms);

            // Parse Form 8995 for additional validation
            Form8995Data form8995 = parseForm8995(forms);

            // Validate each Schedule F against Form 8582
            for (ScheduleFData scheduleF : scheduleFList) {
                validateScheduleF(scheduleF, form8582, form8995, result);
            }

            // Validate totals
            validateTotals(scheduleFList, form8582, payload, result);

        } catch (Exception e) {
            result.getErrors().add("Validation error: " + e.getMessage());
            result.setValid(false);
        }

        result.setValid(result.getErrors().isEmpty());
        return result;
    }

    /**
     * Parses all Schedule F forms from the payload.
     */
    private List<ScheduleFData> parseScheduleFForms(JsonNode forms) {
        List<ScheduleFData> scheduleFList = new ArrayList<>();

        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            if (SCHEDULE_F_FORM.equals(formNum)) {
                ScheduleFData scheduleF = parseSingleScheduleF(form);
                scheduleFList.add(scheduleF);
            }
        }

        return scheduleFList;
    }

    /**
     * Parses a single Schedule F form.
     */
    private ScheduleFData parseSingleScheduleF(JsonNode form) {
        ScheduleFData data = new ScheduleFData();
        data.setSequenceNum(getTextValue(form, "sequenceNum"));

        for (JsonNode lineItem : form.get("lineItems")) {
            String lineName = getTextValue(lineItem, "lineNameTxt");
            String value = getTextValue(lineItem, "perReturnValueTxt");

            if (lineName.contains("FarmProprietorName/BusinessNameLine1Txt")) {
                data.setBusinessName(value);
            } else if (lineName.endsWith("/EIN")) {
                data.setEin(value);
            } else if (lineName.endsWith("/PrincipalProductDesc")) {
                data.setPrincipalProduct(value);
            } else if (lineName.endsWith("/MateriallyParticipatedInd")) {
                data.setMateriallyParticipated("true".equalsIgnoreCase(value));
            } else if (lineName.contains("FarmIncomeCashMethodGrp/GrossIncomeAmt")) {
                data.setGrossIncome(parseBigDecimal(value));
            } else if (lineName.contains("FarmExpensesGrp/TotalExpensesAmt")) {
                data.setTotalExpenses(parseBigDecimal(value));
            } else if (lineName.contains("FarmExpensesGrp/NetFarmProfitLossAmt")) {
                data.setNetProfitLoss(parseBigDecimal(value));
            }
        }

        // Calculate actual profit/loss: Line 9 - Line 33
        if (data.getGrossIncome() != null && data.getTotalExpenses() != null) {
            data.setCalculatedNet(data.getGrossIncome().subtract(data.getTotalExpenses()));
        }

        return data;
    }

    /**
     * Parses Form 8582 data.
     */
    private Form8582Data parseForm8582(JsonNode forms) {
        Form8582Data data = new Form8582Data();
        data.setActivities(new ArrayList<>());

        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            if (FORM_8582.equals(formNum)) {
                for (JsonNode lineItem : form.get("lineItems")) {
                    String lineName = getTextValue(lineItem, "lineNameTxt");

                    if (lineName.contains("ParentWrkshtPassiveGrp/WrkshtPassiveGrp")) {
                        PassiveActivity activity = parsePassiveActivity(lineItem);
                        if (activity.getActivityName() != null) {
                            data.getActivities().add(activity);
                        }
                    } else if (lineName.endsWith("/TotalOtherCurrentYearLossAmt")) {
                        data.setTotalCurrentYearLoss(parseBigDecimal(getTextValue(lineItem, "perReturnValueTxt")));
                    }
                }
                break;
            }
        }

        return data;
    }

    /**
     * Parses a single passive activity from Form 8582.
     */
    private PassiveActivity parsePassiveActivity(JsonNode wrkshtGrp) {
        PassiveActivity activity = new PassiveActivity();

        if (wrkshtGrp.has("lineItems")) {
            for (JsonNode item : wrkshtGrp.get("lineItems")) {
                String lineName = getTextValue(item, "lineNameTxt");
                String value = getTextValue(item, "perReturnValueTxt");

                if (lineName.endsWith("/NonParticipateActivityNm")) {
                    activity.setActivityName(value);
                } else if (lineName.endsWith("/CurrentYearNetLossAmt")) {
                    activity.setCurrentYearLoss(parseBigDecimal(value));
                } else if (lineName.endsWith("/OverallLossAmt")) {
                    activity.setOverallLoss(parseBigDecimal(value));
                }
            }
        }

        return activity;
    }

    /**
     * Parses Form 8995 data for QBI validation.
     */
    private Form8995Data parseForm8995(JsonNode forms) {
        Form8995Data data = new Form8995Data();
        data.setBusinesses(new ArrayList<>());

        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            if (FORM_8995.equals(formNum)) {
                for (JsonNode lineItem : form.get("lineItems")) {
                    String lineName = getTextValue(lineItem, "lineNameTxt");

                    if (lineName.contains("QualifiedBusinessIncomeDedGrp")) {
                        QBIBusiness business = parseQBIBusiness(lineItem);
                        if (business.getBusinessName() != null) {
                            data.getBusinesses().add(business);
                        }
                    } else if (lineName.endsWith("/TotQlfyBusinessIncomeOrLossAmt")) {
                        data.setTotalQBI(getTextValue(lineItem, "perReturnValueTxt"));
                    }
                }
                break;
            }
        }

        return data;
    }

    /**
     * Parses a single QBI business from Form 8995.
     */
    private QBIBusiness parseQBIBusiness(JsonNode qbiGrp) {
        QBIBusiness business = new QBIBusiness();

        if (qbiGrp.has("lineItems")) {
            for (JsonNode item : qbiGrp.get("lineItems")) {
                String lineName = getTextValue(item, "lineNameTxt");
                String value = getTextValue(item, "perReturnValueTxt");

                if (lineName.contains("TradeOrBusinessName/BusinessNameLine1Txt")) {
                    business.setBusinessName(value);
                } else if (lineName.endsWith("/EIN")) {
                    business.setEin(value);
                } else if (lineName.endsWith("/QlfyBusinessIncomeOrLossAmt")) {
                    business.setQbiAmount(parseBigDecimal(value));
                }
            }
        }

        return business;
    }

    /**
     * Validates a single Schedule F against Form 8582.
     */
    private void validateScheduleF(ScheduleFData scheduleF, Form8582Data form8582,
                                        Form8995Data form8995, ValidationResult result) {
        String businessName = scheduleF.getBusinessName();
        BigDecimal calculatedNet = scheduleF.getCalculatedNet();
        BigDecimal reportedNet = scheduleF.getNetProfitLoss();

        // Rule 1: If materially participated, should NOT be on Form 8582
        if (scheduleF.isMateriallyParticipated()) {
            Optional<PassiveActivity> foundIn8582 = findActivityIn8582(businessName, form8582);
            if (foundIn8582.isPresent()) {
                result.getErrors().add(String.format(
                        "ERROR: '%s' is marked as materially participated but appears on Form 8582 (passive activities only)",
                        businessName));
            }
            // Materially participated activities should show full profit/loss
            if (calculatedNet != null && reportedNet!= null && 
                calculatedNetcompareTo(reportedNet) != 0) {
                result.getErrors().add(String.format(
                        "ERROR: '%s' calculated net (%s) does not match reported net (%s)",
                        businessName, calculatedNet, reportedNet));
            }
            return;
        }

        // Rule 2: If NOT participated and has a loss, must be on Form 8582
        if (calculatedNet != null && calculatedNetcompareTo(BigDecimal.ZERO) < 0) {
            Optional<PassiveActivity> foundIn8582 = findActivityIn8582(businessName, form8582);

            if (foundIn8582.isEmpty()) {
                result.getErrors().add(String.format(
                        "ERROR: '%s' has a loss (%s) but is not found on Form 8582",
                        businessName, calculatedNet)>ÂˆH[ÙHÂˆËÈ[HÎˆ™\šYHÜÜÈ[[İ[X]Ú\Âˆ\ÜÚ]™PXİ]š]HXİ]š]HH›İ[™[N‹™Ù]

NÂˆšYÑXÚ[X[^XİYÜÜÈHØ[İ[]Y™]˜XœÊ
NÈËÈÛÛ™\ÈÜÚ]]™H›ÜˆÛÛ\\š\ÛÛ‚ˆšYÑXÚ[X[XİX[ÜÜÈHXİ]š]K™Ù]İ\œ™[YX\“ÜÜÊ
NÂ‚ˆYˆ
XİX[ÜÜÈOH[^XİYÜÜË˜ÛÛ\\™UÊXİX[ÜÜÊHOH
HÂˆ™\İ[™Ù]\œ›ÜœÊ
K˜Y
İš[™Ë™›Ü›X]
ˆ‘T”“Ôˆ	É\ÉÈØ[İ[]YÜÜÈ
	\ÊHÙ\È›İX]Ú›Ü›HNˆÜÜÈ
	\ÊH‹ˆ\Ú[™\ÜÓ˜[YK^XİYÜÜËXİX[ÜÜÊJNÂˆB‚ˆËÈ[HˆØÚY[Hˆ[™HÍÚİ[ÚİÈ	›Üˆ\ÜÚ]™HÜÜÙ\ÂˆYˆ
™\ÜY™]OH[	‰ˆ™\ÜY™]ÛÛ\\™UÊšYÑXÚ[X[–‘T“ÊHOH
HÂˆ™\İ[™Ù]Ø\›š[™ÜÊ
K˜Y
İš[™Ë™›Ü›X]
ˆ•ĞT“’S‘Îˆ	É\ÉÈØÚY[Hˆ[™HÍÚİÜÈ	\È]Úİ[ÚİÈ	›Üˆ\ÜÚ]™HÜÜÙ\È‹ˆ\Ú[™\ÜÓ˜[YK™\ÜY™]
JNÂˆBˆBˆB‚ˆËÈ[HNˆYˆ\ÜÚ]™HÚ]›Ùš]Úİ[“Õ™HÛˆ›Ü›HN‚ˆYˆ
Ø[İ[]Y™]ˆOH[	‰ˆØ[İ[]Y™]˜ÛÛ\\™UÊšYÑXÚ[X[–‘T“ÊHˆ
HÂˆÜ[Û˜[\ÜÚ]™PXİ]š]Oˆ›İ[™[NˆHš[™Xİ]š]R[NŠ\Ú[™\ÜÓ˜[YK›Ü›NNŠNÂˆYˆ
›İ[™[N‹š\Ô™\Ù[

JHÂˆ™\İ[™Ù]Ø\›š[™ÜÊ
K˜Y
İš[™Ë™›Ü›X]
ˆ•ĞT“’S‘Îˆ	É\ÉÈ\ÈH›Ùš]
	\ÊH]\X\œÈÛˆ›Ü›HNˆ
\XØ[HÜÜÙ\ÈÛ›JH‹ˆ\Ú[™\ÜÓ˜[YKØ[İ[]Y™]ÊO°¢Ğ¢Ğ ¢òò'VÆRc¢fÆ–FFRv–ç7Bf÷&Òƒ““R$¢fÆ–FFTv–ç7Df÷&Óƒ““R‡66†VGVÆTbÂf÷&Óƒ““RÂ&W7VÇB“°¢Ğ ¢ò¢ ¢¢fÆ–FFW2F÷FÇ27&÷72ÆÂf÷&×2à¢¢ğ¢&—fFRfö–BfÆ–FFUF÷FÇ2„Æ—7CÅ66†VGVÆTdFFâ66†VGVÆTdÆ—7BÂf÷&ÓƒSƒ$FFf÷&ÓƒSƒ"À¢§6öäæöFR–ÆöBÂfÆ–FF–öå&W7VÇB&W7VÇB’°¢òò6Æ7VÆFRF÷FÂæWBf&Ò–æ6öÖRg&öÒÆÂ66†VGVÆRg0¢&–tFV6–ÖÂF÷FÄ6Æ7VÆFVDæW@cÒ&6†VGVÆTdÆ—7Bç7G&VÒ‚¢æÖ…66†VGVÆTdFF£¦vWD6Æ7VÆFVDæW@ò8b b b b b bÌdğGD°ˆBÓââÒçVÆÂ¢ç&VGV6R„&–tFV6–ÖÂå¤U$òÂ&–tFV6–ÖÃ£¦FB“° ¢òòvWB&W÷'FVBæWBf&Ò–æ6öÖRg&öÒf÷&ÒC66†VGVÆR¢&–tFV6–ÖÂ&W÷'FVDæWDf&Ô–æ6öÖRÒvWDæWDf&Õ&öf—DÆ÷74g&öÓC‡–ÆöB“° ¢–b‡&W÷'FVDæWDf&Ô–æ6öÖRÒçVÆÂbb ¢F÷FÄ6Æ7VÆFVDæWBæ6ö×&UFò‡&W÷'FVDæWDf&Ô–æ6öÖR’Ò’°¢&W7VÇBævWDW'&÷'2‚’æFB…7G&–æræf÷&ÖB€¢$U%$õ#¢F÷FÂ6Æ7VÆFVBæWBf&Ò–æ6öÖR‚W2’FöW2æ÷BÖF6‚f÷&ÒC66†VGVÆR‚W2’"À¢F÷FÄ6Æ7VÆFVDæWA"'W÷'FVDæWDf&Ô–æ6öÖR’“°¢Ğ ¢òòfÆ–FFRF÷FÂ76—fRÆ÷76W0¢&–tFV6–ÖÂF÷FÅ76—fTÆ÷76W2Ò66†VGVÆTdÆ—7Bç7G&VÒ‚¢æf–ÇFW"‡2Óâ2æ—4ÖFW&–ÆÇ•'F–6—FVB‚’¢æÖ…2Óâ2ævWD6Æ7VÆFVDæW@ò“à¢æf–ÇFW"†âÓââÒçVÆÂbbâæ6ö×&UFò„&–tFV6–ÖÂå¤U$ò’Â¢æÖ„&–tFV6–ÖÃ£¦'2¢ç&VGV6R„&–tFV6–ÖÂå¤U$òÂ&–tFV6–ÖÃ£¦FB“° ¢–b†f÷&ÓƒSƒ"ævWEF÷FÄ7W'&VçE–V$Æ÷72‚’ÒçVÆÂbb ¢F÷FÅ76—fTÆ÷76W2æ6ö×&UFò†f÷&ÓƒSƒ"ævWEF÷FÄ7W'&VçE–V$Æ÷72‚’’Ò’°¢&W7VÇBævWDW'&÷'2‚’æFB…7G&–æræf÷&ÖB€¢$U%$õ#¢F÷FÂ6Æ7VÆFVB76—fRÆ÷76W2‚W2’FöW2æ÷BÖF6‚f÷&ÒƒSƒ"F÷FÂ‚W2’"À¢F÷FÅ76—fTÆ÷76W2Âf÷&ÓƒSƒ"ævWEF÷FÄ7W'&VçE–V$Æ÷72‚’’“°¢Ğ¢Ğ ¢ò¢ ¢¢fÆ–FFW266†VGVÆRbv–ç7Bf÷&Òƒ““R$’à¢¢ğ¢&—fFRfö–BfÆ–FFTv–ç7Df÷&Óƒ““R…66†VGVÆTdFF66†VGVÆTbÂf÷&Óƒ““TFFf÷&Óƒ““RÀ¢fÆ–FF–öå&W7VÇB&W7VÇB’°¢÷F–öæÃÅ$”'W6–æW73â&”'W6–æW72Òf÷&Óƒ““RævWD'W6–æW76W2‚’ç7G&VÒ‚¢æf–ÇFW"†"Óâ66†VGVÆTbævWD'W6–æW74æÖR‚’æWVÇ4–væ÷&T66R†"ævWD'W6–æW74æÖR‚’’¢æf–æDf—'7B‚“° ¢–b‡&”'W6–æW72æ—5&W6VçB‚’’°¢&–tFV6–ÖÂ&”Ö÷VçBÒ&”'W6–æW72ævWB‚’ævWE&”Ö÷VçB‚“°¢&–tFV6–ÖÂ6Æ7VÆFVDæWBÒ66†VGVÆTbævWD6Æ7VÆFVDæWB‚“° ¢òò$’6†÷VÆBÖF6‚F†RæWB–æ6öÖRg&öÒ66†VGVÆR`¢–b‡&”Ö÷VçBÒçVÆÂbb6Æ7VÆFVDæWBÒçVÆÂbb ¢&”Ö÷VçBæ6ö×&UFò†6Æ7VÆFVDæWB’Ò’°¢&W7VÇBævWEv&æ–æw2‚’æFB…7G&–æræf÷&ÖB€¢%t$ä”äs¢rW2rf÷&Òƒ““R$’‚W2’FöW2æ÷BÖF6‚66†VGVÆRbæWB–æ6öÖR‚W2’"À¢66†VGVÆTbævWD'W6–æW74æÖR‚’Â&”Ö÷VçBÂ6Æ7VÆFVDæW@ò“ì(€€€€€€€€€€€ô(€€€€€€€ô(€€€ô((€€€€¼¨¨(€€€€€¨¥¹‘Ì„Á…ÍÍ¥Ù”…Ñ¥Ù¥Ñä¥¸½É´€àÔàÈ‰ä‰ÕÍ¥¹•ÍÌ¹…µ”¸(€€€€€¨¼(€€€ÁÉ¥Ù…Ñ”=ÁÑ¥½¹…°ñA…ÍÍ¥Ù•Ñ¥Ù¥Ñäø™¥¹‘Ñ¥Ù¥Ñå%¸àÔàÈ¡MÑÉ¥¹œ‰ÕÍ¥¹•ÍÍ9…µ”°½É´àÔàÉ…Ñ„™½É´àÔàÈ¤ì(€€€€€€€É•ÑÕÉ¸™½É´àÔàÈ¹•ÑÑ¥Ù¥Ñ¥•Ì ¤¹ÍÑÉ•…´ ¤(€€€€€€€€€€€€€€€€¹™¥±Ñ•È¡„€´ø‰ÕÍ¥¹•ÍÍ9…µ”¹•ÅÕ…±Í%¹½É•…Í”¡„¹•ÑÑ¥Ù¥Ñå9…µ” ¤¤¤(€€€€€€€€€€€€€€€€¹™¥¹‘¥ÉÍĞ ¤ì(€€€ô((€€€€¼¨¨(€€€€€¨•ÑÌ¹•Ğ™…É´ÁÉ½™¥Ğ½±½ÍÌ™É½´½É´€ÄÀĞÀM¡•‘Õ±”€Ä¸(€€€€€¨¼(€€€ÁÉ¥Ù…Ñ”	¥•¥µ…°•Ñ9•Ñ…ÉµAÉ½™¥Ñ1½ÍÍÉ½´ÄÀĞÀ¡)Í½¹9½‘”Á…å±½…¤ì(€€€€€€€)Í½¹9½‘”™½ÉµÌ€ôÁ…å±½…¹•Ğ ‰‰½‘äˆ¤¹•Ğ ‰™½ÉµÌˆ¤ì(€€€€€€€™½È€¡)Í½¹9½‘”™½É´€è™½ÉµÌ¤ì(€€€€€€€€€€€¥˜€ ‰%ILÄÀĞÁM¡•‘Õ±”Äˆ¹•ÅÕ…±Ì¡•ÑQ•áÑY…±Õ”¡™½É´°€‰™½Éµ9Õ´ˆ¤¤¤ì(€€€€€€€€€€€€€€€™½È€¡)Í½¹9½‘”±¥¹•%Ñ•´€è™½É´¹•Ğ ‰±¥¹•%Ñ•µÌˆ¤¤ì(€€€€€€€€€€€€€€€€€€€¥˜€¡•ÑQ•áÑY…±Õ”¡±¥¹•%Ñ•´°€‰±¥¹•9…µ•QáĞˆ¤¹•¹‘Í]¥Ñ  ˆ½9•Ñ…ÉµAÉ½™¥Ñ1½ÍÍµĞˆ¤¤ì(€€€€€€€€€€€€€€€€€€€€€€€É•ÑÕÉ¸Á…ÉÍ•	¥•¥µ…°¡•ÑQ•áÑY…±Õ”¡±¥¹•%Ñ•´°€‰Á•ÉI•ÑÕÉ¹Y…±Õ•QáĞˆ¤¤ì(€€€€€€€€€€€€€€€€€€€ô(€€€€€€€€€€€€€€€ô(€€€€€€€€€€€ô(€€€€€€€ô(€€€€€€€É•ÑÕÉ¸¹Õ±°ì(€€€ô((€€€€¼¼!•±Á•Èµ•Ñ¡½‘Ì(€€€ÁÉ¥Ù…Ñ”MÑÉ¥¹œ•ÑQ•áÑY…±Õ”¡)Í½¹9½‘”¹½‘”°MÑÉ¥¹œ™¥•±‘9…µ”¤ì(€€€€€€€¥˜€¡¹½‘”€ôô¹Õ±°ñğ€…¹½‘”¹¡…Ì¡™¥•±‘9…µ”¤¤É•ÑÕÉ¸¹Õ±°ì(€€€€€€€)Í½¹9½‘”™¥•±€ô¹½‘”¹•Ğ¡™¥•±‘9…µ”¤ì(€€€€€€€É•ÑÕÉ¸™¥•±¹¥Í9Õ±° ¤€ü¹Õ±°€è™¥•±¹…ÍQ•áĞ ¤ì(€€€ô((€€€ÁÉ¥Ù…Ñ”	¥•¥µ…°Á…ÉÍ•	¥•¥µ…°¡MÑÉ¥¹œÙ…±Õ”¤ì(€€€€€€€¥˜€¡Ù…±Õ”€ôô¹Õ±°ñğÙ…±Õ”¹¥Í	±…¹¬ ¤¤É•ÑÕÉ¸¹Õ±°ì(€€€€€€€ÑÉäì(€€€€€€€€€€€É•ÑÕÉ¸¹•Ü	¥•¥µ…°¡Ù…±Õ”¤ì(€€€€€€€ô…Ñ €¡9Õµ‰•É½Éµ…Ñá•ÁÑ¥½¸”¤ì(€€€€€€€€€€€É•ÑÕÉ¸¹Õ±°ì(€€€€€€€ô(€€€ô((€€€€¼¼…Ñ„±…ÍÍ•Ì(€€€…Ñ„(€€€9½ÉÍ½¹ÍÑÉÕÑ½È(€€€±±ÉÍ½¹ÍÑÉÕÑ½È(€€€	Õ¥±‘•È(€€€ÁÕ‰±¥ŒÍÑ…Ñ¥Œ±…ÍÌY…±¥‘…Ñ¥½¹I•ÍÕ±Ğì(€€€€€€€ÁÉ¥Ù…Ñ”‰½½±•…¸Ù…±¥ì(€€€€€€€ÁÉ¥Ù…Ñ”1¥ÍĞñMÑÉ¥¹œø•ÉÉ½ÉÌì(€€€€€€€ÁÉ¥Ù…Ñ”1¥ÍĞñMÑÉ¥¹œøİ…É¹¥¹Ìì(€€€ô((€€€…Ñ„(€€€ÁÕ‰±¥ŒÍÑ…Ñ¥Œ±…ÍÌM¡•‘Õ±•…Ñ„ì(€€€€€€€ÁÉ¥Ù…Ñ”MÑÉ¥¹œÍ•ÅÕ•¹•9Õ´ì(€€€€€€€ÁÉ¥Ù…Ñ”MÑÉ¥¹œ‰ÕÍ¥¹•ÍÍ9…µ”ì(€€€€€€€ÁÉ¥Ù…Ñ”MÑÉ¥¹œ•¥¸ì(€€€€€€€ÁÉ¥Ù…Ñ”MÑÉ¥¹œÁÉ¥¹¥Á…±AÉ½‘ÕĞì(€€€€€€€ÁÉ¥Ù…Ñ”‰½½±•…¸µ…Ñ•É¥…±±åA…ÉÑ¥¥Á…Ñ•ì(€€€€€€€ÁÉ¥Ù…Ñ”	¥•¥µ…°É½ÍÍ%¹½µ”ì€¼¼1¥¹”€ä(€€€€€€€ÁÉ¥Ù…Ñ”	¥•¥µ…°Ñ½Ñ…±áÁ•¹Í•Ìì€¼¼1¥¹”€ÌÌ(€€€€€€€ÁÉ¥Ù…Ñ”	¥•¥µ…°¹•ÑAÉ½™¥Ñ1½ÍÌì€¼¼1¥¹”€ÌĞ€¡É•Á½ÉÑ•¤(€€€€€€€ÁÉ¥Ù…Ñ”	¥•¥µ…°…±Õ±…Ñ•‘9•Ğì€¼¼…±Õ±…Ñ•è1¥¹”€ä€´1¥¹”€ÌÌ(€€€ô((€€€…Ñ„(€€€ÁÕ‰±¥ŒÍÑ…Ñ¥Œ±…ÍÌ½É´àÔàÉ…Ñ„ì(€€€€€€€ÁÉ¥Ù…Ñ”1¥ÍĞñA…ÍÍ¥Ù•Ñ¥Ù¥Ñäø…Ñ¥Ù¥Ñ¥•Ìì(€€€€€€€ÁÉ¥Ù…Ñ”	¥•¥µ…°Ñ½Ñ…±ÕÉÉ•¹Ñe•…É1½ÍÌì(€€€ô((€€€…Ñ„(€€€ÁÕ‰±¥ŒÍÑ…Ñ¥Œ±…ÍÌA…ÍÍ¥Ù•Ñ¥Ù¥Ñäì(€€€€€€€ÁÉ¥Ù…Ñ”MÑÉ¥¹œ…Ñ¥Ù¥Ñå9…µ”ì(€€€€€€€ÁÉ¥Ù…Ñ”	¥•¥µ…°ÕÉÉ•¹Ñe•…É1½ÍÌì(€€€€€€€ÁÉ¥Ù…Ñ”	¥•¥µ…°½Ù•É…±±1½ÍÌì(€€€ô((€€€…Ñ„(€€€ÁÕ‰±¥ŒÍÑ…Ñ¥Œ±…ÍÌ½É´àääÕ…Ñ„ì(€€€€€€€ÁÉ¥Ù…Ñ”1¥ÍĞñE	%	ÕÍ¥¹•ÍÌø‰ÕÍ¥¹•ÍÍ•Ìì(€€€€€€€ÁÉ¥Ù…Ñ”MÑÉ¥¹œÑ½Ñ…±E	$ì(€€€ô((€€€…Ñ„(€€€ÁÕ‰±¥ŒÍÑ…Ñ¥Œ±…ÍÌE	%	ÕÍ¥¹•ÍÌì(€€€€€€€ÁÉ¥Ù…Ñ”MÑÉ¥¹œ‰ÕÍ¥¹•ÍÍ9…µ”ì(€€€€€€€ÁÉ¥Ù…Ñ”MÑÉ¥¹œ•¥¸ì(€€€€€€€ÁÉ¥Ù…Ñ”	¥•¥µ…°Å‰¥µ½Õ¹Ğì(€€€ô)ô(