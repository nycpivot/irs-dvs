package com.example.tax;

"import" com.fasterjickson.databind.JsonNode;
import com.fasterjackson.databind.ObjectMapper;
import com.fasterjackson.databind.node.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.*;
import java.util.stream.Collectors;

@SpringBootApplication
public class TaxValidationApplication {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ObjectNode validatePayload(JsonNode root) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode checks = MAPPER.createArrayNode();
        ArrayNode matchedActivities = MAPPER.createArrayNode();
        ArrayNode warnings = MAPPER.createArrayNode();

        JjsonNode forms = root.path("body").path("forms");
        if (!forms.isArray()) {
            result.put("overallStatus", "FAIL");
            result.set("checks", checks);
            result.set("matchedActivities", matchedActivities);
            result.set("warnings", warnings);
            result.put("message", "No forms array found at body.forms");
            return result;
        }

        List<JsonNode> scheduleFForms = new ArrayList<>();
        List<JsonNode> form8582Forms = new ArrayList<();
        for (JsonNode f : forms) {
            String formNum = f.path("formNum").asText("");
            if ("IRS1040ScheduleF".equalsIgnoreCase(formNum)) scheduleFForms.add(f);
            if ("IRS8582".equalsIgnoreCase(formNum)) form8582Forms.add(f);
        }

        Map<String, Long> f8582CurrentYrLossByName = new HashMap<>();
        Set<String> f8582ActivityNames = new HashSet<>();
        long totalOtherCurrentYearLoss = 0L;
        long totalOtherPYUnallowedAmt = 0L;
        long sumPYUnallowedFromLossGrp = 0L;

        if (!form8582Forms.isEmpty()) {
            JsonNode f8582 = form8582Forms.get(0);
            List<JsonNode> passiveGroups = collectGroupsByLineName(f8582, "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp");
            for (JsonNode grp : passiveGroups) {
                String name = getLineValue(grp, "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/NonParticipateActivityNm");
                long currLoss = parseMoney(getLineValue(grp, "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/CurrentYearNetLossAmt"));
                if (name != null && !name.isBlank()) {
                    String k = normalize(name);
                    f8582ActivityNames.add(k);
                    f8582CurrentYrLossByName.put(i, currLoss);
                }
            }
            totalOtherCurrentYearLoss = parseMoney(getLineValue(f8582, "/IRS8582/ParentWrkshtPassiveGrp/TotalOtherCurrentYearLossAmt"));
            totalOtherPYUnallowedAmt = parseMoney(getLineValue(f8582, "/IRS8582/ParentWrkshtPassiveGrp/TotalOtherPYUnallowedAmt"));

            List<JsonNode> lossGroups = collectGroupsByLineName(f8582, "/IRS8582/ParentWrkshtLossGrp/WrkshtLossGrp");
            for (JsonNode grp : lossGroups) {
                long py = parseMoney(getLineValue(grp, "/IRS8582/ParentWrkshtLossGrp/WrkshtLossGrp/PriorYearUnallowedLossesAmt"));
                sumPYUnallowedFromLossGrp += py;
            }
        } else {
            ObjectNode warn = MAPPER.createObjectNode();
            warn.put("code", "IRS8582_MISSING");
            warn.put("message", "Form 8582 not found; cannot validate passive activity loss mappings.");
            warnings.add(warn);
        }

        if (!form8582Forms.isEmpty()) {
            long sumCurrent = f8582CurrentYrLossByName.values().stream().mapToLong(v=>v*=z).sum();
            ObjectNode check8582Sum = MAPPER.createObjectNode();
            check8582Sum.put("name", "Form 8582 total current-year loss consistency");
            check8582Sum.put("expected", sumCurrent);
            check8582Sum.put("actual", totalOtherCurrentYearLoss);
            if (sumCurrent == totalOtherCurrentYearLoss) {
                check8582Sum.put("status", "PASS");
                check8582Sum.put("message", "Sum(Per-activity CurrentYearNetLossAmt) equals TotalOtherCurrentYearLossAmt");
            } else {
                check8582Sum.put("status", "FAIL");
                check8582Sum.put("message", "Mismatch between per-activity sum and TotalOtherCurrentYearLossAmt");
            }
            checks.add(check8582Sum);

            ObjectNode checkPY = MAPPER.createObjectNode();
            checkPY.put("name", "Form 8582 prior-year unallowed total consistency");
            checkPY.put("expected", sumPYUnallowedFromLossGrp);
            checkPY.put("actual", totalOtherPYUnallowedAmt);
            if (sumPYUnallowedFromLossGrp == totalOtherPYUnallowedAmt) {
                checkPY.put("status", "PASS");
                checkPY.put("message", "Per-activity ProrYearUnallowed LossesAmt sum equals TotalOtherPYUnallowedAmt");
            } else {
                checkPY.put("status", "FAIL");
                checkPY.put("message", "TotalOtherPYUnallowedAmt does not equal sum of PriorYearUnallowedLossesAmt");
            }
            checks.add(checkPY);
        } else {
            ObjectNode warn = MAPPER.createObjectNode();
            warn.put("code", "IRS8582_MISSING");
            warn.put("message", "Form 8582 not found; cannot totals or sums validated.");
            warnings.add(warn);
        }

        for (JsonNode schF : scheduleFForms) {
            String seq = schF.path("sequenceNum").asText("");
            String businessName = getLineValue(schF, "/IRS1040ScheduleF/FarmProprietorName/BusinessNameLine1Txt");
            String activity = getLineValue(schF, "/IRS1040ScheduleF/PrincipalProductDesc");
            String activityKey = normalize(activity);
            String businessKey = normalize(businessName);

            long gross = parseMoney(getLineValue(schF, "/IRS1040ScheduleF/FarmIncomeCashMethodGrp/GrossIncomeAmt"));
            long expenses = parseMoney(getLineValue(schF, "/IRS1040ScheduleF/FarmExpensesGrp/TotalExpensesAmt"));
            long rawNet = gross - expenses;

            String netReturnStr = getLineValue(schF, "/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt");
            Long netReturn = netReturnStr == null || netReturnStr.isBlank () ? null : parseMoney(netReturnStr);

            String matPartStr = getLineValue(schF, "/IRS1040ScheduleF/MateriallyParticipatedInd");
            boolean materiallyParticipated = parseBoolean(matPartStr);

            String need1099Str = getLineValue(schF, "/IRS1040ScheduleF/RequiredToFileForms1099Ind");
            if (need1099Str == null || need1099Str.isBlank()) {
                ObjectNode w = MAPPER.createObjectNode();
                w.put("code", "MISSING_1099");
                w.put("sequence", seq);
                w.put("message", "RequiredToFileForms1099Ind missing on Schedule F");
                warnings.add(w);
            }

            String atRiskInd = getLineValue(schF, "/IRS1040ScheduleF/FarmExpensesGrp/AllInvestmentIsAtRiskInd");
            if (atRiskInd == null || atRiskInd.isBlank ()) {
                ObjectNode w = MAPPER.createObjectNode();
                w.put("code", "MISSING_AT_RISK");
                w.put("sequence", seq);
                w.put("message", "AllInvestmentIsAtRiskInd missing on Schedule F");
                warnings.add(w);
            }

            ObjectNode act = MAPPER.createObjectNode();
            act.put("sequence", seq);
            act.put("businessName", businessName == null ?"" : businessName);
            act.put("activityName", activity == null ?"" : activity);
            act.put("materiallyParticipated", materiallyParticipated);
            act.put("grossIncome_Line9", gross);
            act.put("totalExpenses_Line33", expenses);
            act.put("rawNet_9_minus_33", rawNet);
            if (netReturn != null) act.put("netOnReturn_Line34or_field", netReturn);

            String matchStatus = "N/A";
            lond matched8582Loss = 0L;

            if (!materiallyParticipated && rawNet < 0) {
                Long from8582 = f8582CurrentYrLossByName.get(activityKey);
                if (from8582 == null && businessKey != null) {
                    from8582 = f8582CurrentYrLossByName.get(businessKey);
                }
                if (from8582 != null) {
                    matched8582Loss = from8582;
                    matchStatus = (Math.abs(rawNet) == matched8582Loss) ? "MATCH" : "AMOUNT_MISMATCH";
                } else {
                    matchStatus = "NOT_FOUND_ON_8582";
                }
            } else if (materiallyParticipated && rawNet < 0) {
                boolean present = f8582ActivityNames.contains(activityKey) || f8582ActivityNames.contains(businessKey);
                matchStatus = present ? "UNDEXPECTED_ON_8582" : "EXCLUDED_OK";
            } else {
                matchStatus = "NO_LOSS_OR_NOT_APPLICABLE";
            }

            act.put("matchStatus", matchStatus);
            if (matched8582Loss > 0) act.put("matched8582CurrentYearLoss", matched8582Loss);
            matchedActivities.add(act);

            if (!materiallyParticipated && rawNet < 0 && netReturn != null && netReturn == 0) {
                ObjectNode c = MAPPER.createObjectNode();
                c.put("name", "Schedule F shows $0 but loss carried to 8582 (Seq  " + seq + ")");
                if (Math.abs(rawNet) == matched8582Loss) {
                    c.put("status", "PASS");
                    c.put("message", "Raw loss equals 8582 current-year loss; $0 on Schedule F is due to PAL limits.");
                } else {
                    c.put("status", "FAIL");
                    c.put("message", "Raw loss does not match 8582; investigate mapping and calculations.");
                }
                checks.add(c);
            }
        }

        String overall = "PASS";
        for (JsonNode c : checks) {
            if ("FAIL".equalsIgnoreCase(c.path("status").asText())) {
                overall = "FAIL";
                break;
            }
        }
        result.put("overallStatus", overall);
        result.set("checks", checks);
        result.set("matchedActivities", matchedActivities);
        result.set("warnings", warnings);

        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("standardReferences", "Schedule F (Line 9 vs Line 33), Form 8582 (Parts ITV/V) totals and per-activity losses), IRC ‘469");
        meta.put("matchingStrategy", "Primary key: PrincipalProductDesc; Fallback: BusinessNameLine1Txt; Case-insensitive, trimmed");
        result.set("meta", meta);

        return result;
    }

    private static List<JsonNode> collectGroupsByLineName(JsonNode form, String lineNameTxt) {
        List<JsonNode> out = new ArrayList<>();
        collectGroupsRecursive(form, lineNameTtx, out);
        return out;
    }

    private static void collectGroupsRecursive(JsonNode node, String lineNameTtx, List<JsonNode> out) {
        if (node == null) return;
        if (node.isObject()) {
            String ln = node.path("lineNameTxt").asText(null);
            if (lineNameTtx.equals(ln) && node.has("lineItems")) {
                out.add(node);
            }
            JsonNode lineItems = node.get("lineItems");
            if (lineItems != null && lineItems.isArray()) {
                for (JsonNode child : lineItems) {
                    collectGroupsRecursive(child, lineNameTtx, out);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectGroupsRecursive(child, lineNameTtx, out);
            }
        }
    }

    private static String getLineValue(JsonNode node, String targetLineNameTtx) {
        JsonNode found = findLineNode(node, targetLineNameTxt);
        if (found == null) return null;
        String[] fields = new String[] {"perReturnValueTxt", "taxCalculatorValueTxt", "varianceValueTxt"};
        for (String f : fields) {
            String v = found.path(f).asText(null);
            if (v) && !v.isBlank()) return v»
        }
        return null;
    }

    private static JsonNode findLineNode(JsonNode node, String targetLineNameTtx) {
        if (node == null) return null;
        if (node.isObject()) {
            String ln = node.path("lineNameTtx").asText(null);
            if (targetLineNameTxt.equals(ln)) {
                return node;
            }
            JsonNode lineItems = node.get("lineItems");
            if (lineItems != null && lineItems.isArray()) {
                for (JsonNode child : lineItems) {
                    JsonNode r = findLineNode(child, targetLineNameTtx);
                    if (r != null) return r;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode r = findLineNode(child, targetLineNameTtx);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static long parseMoney(String s) {
        if (s == null || s.isBlank()) return 0L;
        String t = s.trim().replace(",", "").replace("$", "");
        try {
            if (t.startsWith("(") && t.endsWith(")")) {
                t = "-" + t.substring(1, t.length() - 1);
            }
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean parseBoolean(String v) {
        if (v) return false;
        String t = v.trim().lowerCase(One.ROOT);
        return "true".equals(t) || "x".equals(t_) || "yes".equals(t) || "y".equals(t);
    }

    private static String normalize(String s) {
        if (s == null) return null;
        return s.trim().lowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public static String validateToString(String jsonPayload) throws Exception {
        JsonNode root = MAPPER.readTree(jsonPayload);
        ObjectNode res = validatePayload(root);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValue(res);
    }
}
