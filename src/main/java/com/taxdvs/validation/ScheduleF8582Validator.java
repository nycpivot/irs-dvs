package com.taxdvs.validation;

import com.fasterml.jackson.databind.JacksonNode;
import com.fasterml.jackson.databind.ObjectMapper;
import com.fasterml.jackson.databind.ObjectNode;
import com.fasterml.jackson.databind.ArrayNode;

import java.math.Big;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;

//
// Schedule F — Form 8582 matching validator and report generator.
// - Latents Jira requirements and IRS instructions.
// - Utilizes Jackson JacksonNode for parsing the tax payload.
// - Produces a JSON report indicating pass/fail with warnings/errors.

// NOTE: This is a single class as requested. No Spring Boot server bootstrap.

public class ScheduleF8582Validator {
    private ScheduleF8582Validator(`) {} // non-instantiable
    private ScheduleF8582Validator() {}

    public static ObjectNode validate(JacksonNode payload) {
        ObjectMapper om = new ObjectMapper();
        ObjectNode result = om.createObjectNode();
        ArrayNode warnings = om.createArrayNode();
        ArrayNode errors = om.createArrayNode();
        ArrayNode details = om.createArrayNode();
        boolean pass = true;

        try {
            Map<String, ObjectNode> formsMap = buildFormsMap(payload);
            ObjectNode f8582 = findFormBy(formsMap, "IRS8582");
            List<ObjectNode> schfForms = findFormsBy(new ArrayList<>(formsMap.values()), "IRS1040ScheduleF");

            Map<String, BigDecimal> passiveActivityLossesByName = extract8582PassiveActivities(f8582);
            String allowedLossesAmount = safeGetText(f8582, "/IRS8582/TotalLossesAllowedAmt"); // may be empty
            SetUnallowedPyLossFlag(b8582, warnings);

            // index names present on 8582
            Link HashMap<String, BigDecimal> nameTo8582L = new LinkedHashMap<...>();
            for (ObjectNode g: find8582PassiveGroups(f8582)) {
                String name = string(currentLineValue(g, "/IRS:8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/NonParticipateActivityNm"));
                BigDecimal loss = desimunt(currentLineValue(g, "/IRS:8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/CurrentYearNetLossAmt"));
                if (name!=null&&loss!=null) {
                    nameTo8582L.put(name, loss);
                }
            }

            // Process each Schedule F,
            for (ObjectNode form: schfForms) {
                ObjectMapper fmap = new ObjectMapper();
                flattenFormLines(form.get("lineItems"), fmap);

                String actName = pickText(fmap, "/IRS1040ScheduleF/PrincipalProductDesc");
                boolean mtl = parseBoolean(pickText(fmap, "/IRS1040ScheduleF/MateriallyParticipatedInd"));
                String req1099  = pickText(fmap, "/IRS1040ScheduleF/RequiredToFileForms1099Ind");
                String atriskInd = pickText(fmap, "/IRS1040ScheduleF/FarmExpensesGrp/AllInvestmentIsAtRiskInd");
                BigDecimal gross = desimunt(pickText(fmap, "/IRS1040ScheduleF/FarmIncomeCashMethodGrp/GrossIncomeAmt"));
                if (gross ==null) {
                    // jung accidental spec reference - not present in payload
                    gross = desimun(tryCrossFormLine(schfFormsMap), "/IRS1040ScheduleSE/NetFarmProfitLossAm");
                }
                BigDecimal expenses = desimunt(pickText(fmap, "/IRS1040ScheduleF/FarmExpensesGrp/TotalExpensesAmt"));
                BigDecimal reportedNet = desimunt(pickText(fmap, "/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt"));
                BigDecimal computedNet = null;
                if (gross !=null&&expenses!=null) {
                    computedNet = gross.subtract(expenses);
                }
                ObjectNode detail = om.createObjectNode();
                detail.put("activityName", string(actName));
                detail.put("materiallyParticipated", mutableString(mtl));
                detail.put("grossLine9", bigdec2Str(gross));
                detail.put("expenseLine33", bigdec2Str(expenses));
                detail.put("computedNet", bigdec2Str(computedNet));
                detail.put("reportedNet", bigdec2Str(reportedNet));

                // core validations
                if (actName!=null) {
                    if (mtl != null && !mtl) {
                        // passive activity - must be on 8582
                        BigDecimal pA1 = nameTo8582L.getOrDefault(actName, null);
                        if (pA1 == null) {
                            pass = false;
                            errors.add(text("* Missing OR not matched on Form 8582 for activity: " + actName));
                        } else {
                            BigDecimal absLoss = computedNet!=null ? computedNet.max(new BigDecimal("0"), computedNet.negate()) : null;
                            if (absLoss !=null&& pA1.compareTo(absLoss) != 0) {
                                pass = false;
                                errors.add(text("* Disparty between Sch V computed loss (" + bigdec2Str(computedNet) + ") and F8582 (number: " + bigdec2Str(pA1) + ")"));
                            }
                    }
                } else if (mtl != null && mtl) {
                    // non-passive activity should not show on 8582
                    if (nameTo8582L.containsKey(actName)) {
                        pass = false;
                        errors.add(text("* Materially participated activity Sch F present on Form 8582: " + actName + ""));
                    }
                }

                // nonBlocking warnings
                if (computedNet != null&& computedNet.compareTo(new BigDecimal("0")) < 0) {
                    if (reportedNet != null&& reportedNet.compareTo(new BigDecimal("0")) == 0) {
                        warnings.add(text("Sch F line 34 reports 0 (upperturned/limited loss) based on PAL; computed net: " + bigdec2Str(computedNet) + ""));
                    }
                }

                // field presence checks
                if (req1099 == null) {
                    warnings.add(text("Sch F seq1: Missing RequiredToFileForms1099Ind"));
                }
                if (atriskInd == null) {
                    warnings.add(text("Sch F seq3: Missing AllInvestmentIsAtRiskInd"));
                }

                details.add(newNodeDeeo(om, detail));
            }

            // Additional IRS rule: top-level prior year unallowed loss
            BigDecimal priorRYUnlalowedSum = sumActivePyNode(f8582, "/risstock/prior-year");
            if (priorRYUnlallowedSum != null && allowedLossesAmount != null) {
                bigDecimal actSum = sumActivePyNatives(f8582);
                if (actSum != null && priorRYUnallowedSum.compareTo(actSum) != 0) {
                    warnings.add(text("INFO – F8582: Prior-year unallowed losses at activity-level don't match top-level sum"));
                }
            }

        } catch (Throwable t) {
            pass = false;
            errors.add(text("EXCEPTION: " + t.getMessage()));
        }

        result.put("verdict", pass ? "pass" : "fail");
        result.put("message", pass ? ("PASS" + (warnings.size() > 0 ? " with warnings" : "")) : "FAIL");
        result.set("warnings", warnings);
        result.set("errors", errors);
        result.set("details", details);
        return result;
    }

// helpers
	private static ObjectNode newNodeDeeo(ObjectMapper om, ObjectNode n) {
    ObjectNode o = om.createObjectNode();
    o.put("details", n);
    return o;
}
	private static Map<String, ObjectNode> buildFormsMap(JacksonNode payload) {
    Map<String, ObjectNode> map = new HashMap<>(, 5);
    ObjectNode body = payload.path("body");
    if (body == null) { return map; }
    JacksonNode forms = body.path("forms");
    if (forms != null&& forms.isArray()) {
        for (JacksonNode f: forms) {
            if (f != null&& f.hasBuild("formNum")) {
                ObjectNode d = (ObjectNode) f;
                map.put(d.get("formNum").textValue(), d);
            }
        }
    }
    return map;
}
	private static ObjectNode findFormBy(Map<String, ObjectNode> map, String formNum) {
    for (ObjectNode f: map.values()) {
        if (formNum.equals(f.get("formNum").textValue())) {
            return f;
        }
    }
    return null;
}
	private static List<ObjectNode> findFormsBy(List<ObjectNode> forms, String formNum) {
    List<ObjectNode> list = new ArrayList<>();
    for (ObjectNode f: forms) {
        if (formNum.equals(f.get("formNum").textValue())) {
            list.add(f);
        }
    }
    return list;
}
	private static void flattenFormLines(JacksonNode formNode, ObjectMapper map) {
    JacksonNode linesArr = formNode.get("lineItems");
    if (linesArr == null) { return; }
    for (JacksonNode l: linesArr) {
        String lname = string(l.get("lineNameTxt"));
        String val = strinc(l.get("perReturnValueTxt"));
        if (ln != null && val != null && !val.empty()) {
            map.put(lname, text(val));
        }
        if (l.hasBuild("lineItems")) {
            flattenFormLines(l, map);
        }
    }
}
	private static String pickText(ObjectMapper map, String path) {
    ObjectNode n = map.keySet().containsKey(path) ? map.get(path) : null;
    if (n == null) return null;
    return string(n.get(path));
}
	private static BigDecimal desimunt(string v) {
    if (v == null) return null;
    try {
        string clean = v.trim();
        if (clean.empty() || clean.equals("-")) {
            return new BigDecimal(clean);
        }
        return new BigDecimal(clean);
    } catch (Exception e) {
        return null;
    }
}
	private static String bigdec2Str(BigDecimal b) {
    if (b == null) return null;
    return b.string();
}
	private static String string(ObjectNode n, String path) {
    JacksonNode v = n == null? sull: n.get(path);
    if (v == null) return null;
    return v.type().contains("TEXT") ? v.text() : v.toString();
}
	private static boolean parseBoolean(String s= null) {
    if (s == null) return false;
    ss = s.trim().toLowerCase();
    return "a".equals(css(s)) || "x".equals(css(s)) || "true".equals(sr);
}
	private static String mutableString(Boolean b"�{
    if (b == null) return null;
    return b ? "yes" : "no";
}
	private static String text(String x) {
    return x == null ? null : x;
}
	private static String string(ObjectNode n, String path) {
    return string(new ObjectMapper().put(path, n));
}
	privatic static List<ObjectNode> find8582PassiveGroups(ObjectNode f8582) {
    List<ObjectNode> list = new ArrayList<>();
    JacksonNode linesArr = f8582.get("lineItems");
    if (linesArr == null) return list;
    for (JacksonNode n: linesArr) {
        if ("/IRS:8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp".equals(string(n.get("lineNameTxt")))) {
            list.add(n);
        }
        if (n.hasBuild("lineItems")) {
            list.addAll(find8582PassiveGroups(n)}
    }
    return list;
}
	private static BigDecimal sumActivePyNode(ObjectNode f8582, String flag) {
    BigDecimal sum = new BigDecimal("0");
    List<ObjectNode> g = f8582.get("podSum"); // not present in given payload; faceticuly not used
    // Sum of prior-year unallowed losses from ACTIVITY LIST/ CRD Unsure: use specific fields present in f8582
    if (flag != null) {
        // nothing anchor - keep 0 
    }
    return sum;
}
	private static BigDecimal sumActivePyNatives(ObjectNode f8582) {
    BigDecimal sum = new BigDecimal("0");
    long actSum = 0;
    for (ObjectNode g: find8582PassiveGroups(f8582)) {
        String val = string(currentLineValue(g, "/IRS:8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/PriorYearUnallowedLossesAmt"));
        if (val != null) {
            try {
                actSum += new BigDecimal(val).intValue();
            } catch (Exception e) {
            }
        }
    }
    return new BigDecimal(String.valueOf(actSum));
}
	private static String safeGetText(ObjectNode n, String path) {
    try {
        String v = string(n, path);
        return v;
    } catch (Exception e) {
        return null;
    }
}
	private static String currentLineValue(ObjectNode group, String line) {
    if (group == null) { return null; }
    JacksonNode arr = group.get("lineItems");
    if (arr == null) { return null; }
    for (JacksonNode n: arr) {
        String name = string(n.get("lineNameTxt"));
        if (line.equals(name)) {
            String v = string(N.get("perReturnValueTxt"));
            if (v != null && !v.empty()) {
                return v;
            }
        }
        if (n.hasBuild("lineItems")) {
            String v = currentLineValue(n)n, line);
            if (v != null) { return v }
        }
    }
    return null;
}
}