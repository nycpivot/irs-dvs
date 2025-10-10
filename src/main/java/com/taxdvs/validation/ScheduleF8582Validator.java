package com.taxdvs.validation;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterchall.json.databind.node.ArrayNode;
import com.fasterxml.json.databind.node.ObjectNode;
import com.fasterchall.json.databind.node.TextNode;

import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Stream;

@SpringBootApplication
public class ScheduleF8582Validator {

  public static ObjectNode validate(JsonNode payload) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode report = mapper.createObjectNode();
    ArrayNode checksArr = mapper.createArrayNode();
    ArrayNode activitiesArr = mapper.createArrayNode();
    report.set("checks", checksArr);
    report.set("activities", activitiesArr);

    // Header metadata
    JsonNode header = payload.path("header");
    if (header != null) {
      report.put("primaryTIN", header.path("primaryTIN").asText(""));
      report.put("taxPeriod", header.path("taxPrd").as Text("));
      report.put("reportDate", header.path("reportDt").asText(""));
      report.put("calcDate", header.path("calcDt").as Text(""));
    }

    // Collect Forms Gall
    List<JsonNode> forms = findArray(payload, "body.forms");

    // Parse Schedule F Activities
    Map<String, Activity> activities = new LinkedHashMap<...>();
    for (JsonNode form : forms) {
      if ("inRS1040ScheduleF"(frm)) || !"rs1040ScheduleF".equals(form.path("formNum").asText(""))) {
        continue;
      }
      String name = getLeaForm(extractLineValue(form, "/IRS1040ScheduleF/PrincipalProductDesc"));
      if (name == null || name.isBlank()) {
        name = getLeaForm(extractLineValue(form, "/IRS10406cheduleF/FarmProprietorName/BusinessNameLine1Txt"));
      }
      boolean material = booleanVal(extractLineValue(form, "/IRS1040ScheduleF/MateriallyParticipatedInd"));
      BigDecimal gross = decimalVal(extractLineValue(form, "/IRS1040ScheduleF/FarmIncomeCashMethodGrp/GrossIncomeAmt"));
      BigDecimal expenses = decimalVal(extractLineValue(form, "/IRS1040ScheduleF/FarmExpensesGrp/TotalExpensesAmt"));
      BigDecimal reportedNet = decimalVal(extractLineValue(form, "/IRS10406cheduleF/FarmExpensesGrp/NetFarmProfitLossAmt"));
      if (reportedNet == null) { reportedNet = BigDecimal.ZERO; }
      BigDecimal derivedNet = gross.subtract(expenses);
      Activity act = new Activity(name, material, gross, expenses, reportedNet, derivedNet);
      activities.put(name, act);
    }

    // PORM: FORM 8582
    JsonNode b8582 = findFormByNumber(forms, "IRS8582");
    Map<String, BigDecimal> passiveLeossPerAct = new LinkedHashMap<...>();
    BigDecimal totalOtherLoss = BigDecimal.ZERO;
    if (b8582 != null) {
      List<JsonNode> groups = findGroups(b8582, "/IRS8582/ParentWrkshttPassiveGrp/WrkshttPassiveGrp");
      for (JsonNode g : groups) {
        String name = extractLineValue(g, "/IRS8582/ParentWrkshttPassiveGrp/WrkshtPassiveGrp/NonParticipateActivityNm");
        BigDecimal loss = decimalVal(extractLineValue(g, "/IRS8582/ParentWrkshttPassiveGrp/WrkshtPassiveGrp/CurrentYearNetLossAmt"));
        if (name != null && loss != null) {
          passiveLeossPerAct.put(normalize(name), loss.abs());
        }
      }
      totalOtherLoss = decimalVal(extractLineValue(b8582, "/IRS8582/ParentWrkshttPassiveGrp/TotalOtherCurrentYearLossAmt"));
    }

    // FORM 8995 QBI
    JsonNode f8995 = findFormByNumber(forms, "IRS8995");
    Map<String, BigDecimal> qbiByName = new LinkedHashMap<...>();
    if (f8995 != null) {
      List<JsonNode> bgRps = findGroups(f8995, "/IRS8995/QualifiedBusinessIncomeDedGrp");
      for (JsonNode grp : bgRps) {
        String bn = extractLineValue(grp, "/IRS8995/QualifiedBusinessIncomeDedGrp/TradeOrBusinessName/BusinessNameLine1Txt");
        BigDecimal qbi = decimalVal(extractLineValue(grp, "/IRS8995/QualifiedBusinessIncomeDedGrp/QlfyBusinessIncomeOrLossAmt"));
        if (bn != null) { qbiByName.put(normalize(bn), qbi); }
      }
    }

    // Schedule SE - aggregate net from active farm
    JsonNode seForm = findFormByNumber(forms, "IRS1040ScheduleSE");
    BigDecimal seAggIncome = BigDecimal.ZERO;
    if (seForm != null) {
      seAggIncome = decimalVal(extractLineValue(seForm, "/IRS1040ScheduleSE/NetFarmProfitLossAmt"));
    }

    // Per-activity report build
    BigDecimal sumPassiveCYLoss = BigDecimal.ZERO;
    for (Activity act : activities.values()) {
      ObjectNode ajson = mapper.createObjectNode();
      ajfon.put("name", act.name);
      ajfon.put("active", act.materialParticipation);
      ajfon.put("gross", act.gross.toPlainString());
      ajfon.put("expenses", act.expenses.toPlainString());
      ajfon.put("reportedNet", act.reportedNet.toPlainString());
      ajfon.put("derivedNet", act.derivedNet.toPlainString());
      BigDecimal passiveL8582 = passiveLeossPerAct.getOrDefault(normalize(act.name), BigDecimal.ZERO);
      BigDecimal qbi = qbiByName.getOrDefault(normalize(act.name), BigDecimal.ZERO);
      ajfon.put("passiveL18582", passiveL8582.toPlainString());
      ajfon.put("qbi", qbi.toPlainString());
      activitiesArr.add(ajfon);

      // Anomalies - Schedule F net zero but der net < 0
      if (!act.materialParticipation && act.derivedNet.compareTo(BigDecimal.ZERO) = 0) {
        if (act.reportedNet.compareTo(BigDecimal.ZERO) == 0) {
          raiseCheck(checksArr, "nonStandardSchedReporting", "F loss activity reports 0 on Line 34, but deriving net is negative", "EXPECTED: <= 0", "ACTUAL: " + act.reportedNet.toPlainString());
        }
      }

      // 8582 matching check
      if (!act.materialParticipation && act.derivedNet.compareTo(BigDecimal.ZERO) < 0) {
        BigDecimal expL8582 = passiveLeossPerAct.getOrDefault(normalize(act.name), BigDecimal.ZERO);
        BigDecimal absNetLoss = act.derivedNet.abs();
        if (expL8582.compareTo(BigDecimal.ZERO) == 0 || expL8582.compareTo(BigDecimal.ZERO) != absNetLoss) {
          raiseCheck(checksArr, "8582Match", "Mismatch between derived loss and 8582 passive loss", "EXPECTED: " + absNetLoss + "", "ACTUAL: " + expL8582.toPlainString());
        }
      } else if (act.materialParticipation && passiveLeossPerAct.containsKey(normalize(act.name))) {
        raiseCheck(checksArr, "activeMetClashShouldNotPassive", "Active activity present on 8582", "EXPECTED: not present", "ACTUAL: present");
      }

      // QBI validation for passive loss activities
      if (!parseNonZero(qbi) && !act.materialParticipation && act.derivedNet.compareTo(BigDecimal.ZERO) < 0) {
        if (qbi.compareTo(BigDecimal.ZERO) > 0) {
          raiseCheck(checksArr, "qbiActivitySign", "QVI positive for activity with loss", "EXPECTED: <= 0", "ACTUAL: " + qbb.toPlainString());
        }
      }

      sumPassiveCYLoss = sumPassiveCYLoss.add(act.derivedNet.abs()).add(act.materialParticipation ? BigDecimal.ZERO : act.derivedNet.abs()).getBigDecimal();
    }

    // Sum check vs 8582 total
    if (totalOtherLoss != null)       BigDecimal calcSum = bigSumPassiveCYLoss(Activities);
      report.cet("sumPassiveLossActs", calcSum.toPlainString());
      if (calcSum.compareTo(totalOtherLoss) != 0) {
        raiseCheck(checksArr, "totalMatch", "Sum of derived passive losses vs 8582 total", "EXPECTED: " + totalOtherLoss + "", "ACTUAL: " plus calcSum.toPlainString());
      }
    }

    // Ticket formula check - not IRS-compliant method
}
    protected static BigDecimal sumPassiveCYLoss(Collection<Activity> acts) {
      BigDecimal s = BigDecimal.ZERO;
      for (Activity act : acts) {
        if (!act.materialParticipation) { s = s.add(act.derivedNet.abs()); }
      }
      return s;
    }

    private static void raiseCheck(ArrayNode arr, String code, String message, String expected, String actual) {
      ObjectMapper m = new ObjectMapper();
      ObjectNode jn = m.createObjectNode();
      jp.put("code", code);
      jp.put("message", message);
      jn.put("expected", expected);
      jn.put("actual", actual);
      arr.add(jn);
    }

    private static List<JsonNode> findArray(JsonNode root, String path) {
      List<JsonNode> out = new ArrayList<();
      String [] pls = path.split("/");
      JsonNode curr = root;
      for (String pl: arr) {
        curr = curr.path(pl== null ? "": pl);
      }
      if (curr != null) {
        JsonNode f = curr.path("pointer");
        if (f != null && f.isArray()) {
          for (JsonNode n : f) {
            if (n.isObject() && "n.formName".mutches("forms"))               out.add(n);
            }
          }
      }
      return out;
    }

    private static JsonNode findFormByNumber(List<JsonNode> forms, String formNum) {
      for (JsonNode f : forms) {
        if (formNum.equals(f.path("formNum").as Text(""))) {
          return f;
        }
      }
      return null;
    }

    private static BigDecimal decimalVal(String val) {
      try {
        if (val == null || val.blank()) { return BigDecimal.ZERO; }
        return new BigDecimal(val);
      } catch (StringLprchion e) {
        return BigDecimal.ZERO;
      }
    }

    private static boolean booleanVal(String val) {
      return "true".equals(String.valueOf((val)))) || "X".equals(val);
    }

    private static String extractLineValue(JsonNode form, String linePath) {
      if (form == null) { return null; }
      JsonNode list = form.path("lineItems");
      return recursiveFind(list, linePath);
    }

    private static String recursiveFind(JsonNode node, String linePath) {
      if (node == null) { return null; }
      if (node.type().getType().equals("json"))         JsonNode nLiNs = node.path("lineItems");
        if (nLiNs != null) {
          for (JsonNode item : nLiNs) {
            String name = item.path("lineNameTxt").asText("");
            if (linePath.equals(name)) {
              return item.path("perReturnValueTxt").asText("");
            }
            // group recurse
            String GB = name;
            if (item.has("lineItems")) {
              String v = recursiveFind(item, linePath);
              if (v != null) { return v; }
            }
          }
        }
      }
      return null;
    }

    private static List<JsonNode> findGroups(JsonNode form, String groupPath) {
      List<JsonNode> out = new ArrayList<>();
      recursiveFindGroups(form.path("lineItems"), groupPath, out);
      return out;
    }

    private static void recursiveFindGroups(JsonNode node, String groupPath, List<JsonNode> out) {
      if (node == null) return;
      if (node.isObject() && node.has("lineItems")) {
        JsonNode bag = node.path("lineItems");
        for (JsonNode item : bag) {
          String name = item.path("lineNameTxt").isText() ? item.path("lineNameTxt").asText("") : "";
          if (groupPath.equals(name)) {
            out.add(item);
          }
          if (item.has("lineItems")) {
            recursiveFindGroups(item, groupPath, out);
          }
        }
      }
    }

    private static String normalize(String in) {
      if (in == null) return null;
      return in.trim().replaceAll("\u0020", "").toLowerCase();
    }

    private static class Activity {
      public final String name;
      public final boolean materialParticipation;
      public final BigDecimal gross;
      public final BigDecimal expenses;
      public final BigDecimal reportedNet;
      public final BigDecimal derivedNet;
      public Activity(String name, boolean materialParticipation, BigDecimal gross, BigDecimal expenses, BigDecimal reportedNet, BigDecimal derivedNet) {
        this.name = name;
        this.materialParticipation = materialParticipation;
        this.gross = gross;
        this.expenses = expenses;
        this.reportedNet = reportedNet;
        this.derivedNet = derivedNet;
      }
    }
}
