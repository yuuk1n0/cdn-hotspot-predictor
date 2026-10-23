package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class AlertCardRenderer {
    private final ObjectMapper mapper;

    public AlertCardRenderer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String render(ObjectNode incident, ObjectNode route, String phase) {
        ObjectNode card = mapper.createObjectNode();
        card.put("schema", "2.0");
        card.putObject("config").put("update_multi", true).put("width_mode", "default");
        ObjectNode header = card.putObject("header");
        header.put("template", template(phase));
        header.putObject("title").put("tag", "plain_text")
                .put("content", "[" + incident.path("severity").asText() + "] "
                        + route.path("businessName").asText(incident.path("appId").asText()));
        header.putObject("subtitle").put("tag", "plain_text")
                .put("content", incident.path("appId").asText() + " · "
                        + details(incident).path("title").asText("线上事故"));
        header.putObject("icon").put("tag", "standard_icon")
                .put("token", "RECOVERY".equals(phase) ? "done_outlined" : "warning_outlined");
        ObjectNode statusTag = header.putArray("text_tag_list").addObject().put("tag", "text_tag");
        statusTag.putObject("text").put("tag", "plain_text")
                .put("content", phase + " · " + incident.path("status").asText());
        statusTag.put("color", tagColor(phase));
        ObjectNode body = card.putObject("body");
        body.put("direction", "vertical").put("padding", "12px 12px 20px 12px")
                .put("vertical_spacing", "8px");
        var elements = body.putArray("elements");
        ObjectNode details = (ObjectNode) incident.path("details");
        ObjectNode metrics = elements.addObject().put("tag", "column_set")
                .put("flex_mode", "bisect").put("horizontal_spacing", "8px");
        var columns = metrics.putArray("columns");
        metric(columns, "当前值", details.path("observedValue").asText("待补充"), "red-50");
        metric(columns, "阈值", details.path("threshold").asText("待补充"), "grey-50");

        ObjectNode fields = elements.addObject().put("tag", "div");
        var fieldArray = fields.putArray("fields");
        field(fieldArray, "事故号", incident.path("incidentId").asText());
        field(fieldArray, "异常对象", incident.path("objectKey").asText());
        field(fieldArray, "开始时间", incident.path("firstSeenAt").asText());
        field(fieldArray, "负责人", mentions(route.path("ownerOpenIds")));
        field(fieldArray, "详情路径", details.path("detailUrl").asText("—"));

        elements.addObject().put("tag", "markdown")
                .put("content", "**影响**\n" + details.path("summary").asText("待补充"));
        if ("RECOVERY".equals(phase)) {
            elements.addObject().put("tag", "markdown").put("content",
                    "<font color='green'>**已恢复**</font>\n恢复时间："
                            + incident.path("recoveredAt").asText("—")
                            + "\n恢复前发生次数：" + incident.path("occurrenceCount").asInt());
        } else {
            ObjectNode ack = elements.addObject().put("tag", "button").put("type", "primary_filled");
            ack.putObject("text").put("tag", "plain_text").put("content", "认领并处理");
            callback(ack, incident, "ACK");
            ObjectNode processing = elements.addObject().put("tag", "button").put("type", "default");
            processing.putObject("text").put("tag", "plain_text").put("content", "更新为处理中");
            callback(processing, incident, "PROCESSING");
            ObjectNode recover = elements.addObject().put("tag", "button").put("type", "danger");
            recover.putObject("text").put("tag", "plain_text").put("content", "标记恢复");
            callback(recover, incident, "RECOVER");
        }
        return card.toString();
    }

    public String testCard(String businessName, String testChatId) {
        ObjectNode card = mapper.createObjectNode();
        card.put("schema", "2.0");
        card.putObject("config").put("update_multi", true).put("width_mode", "compact");
        ObjectNode header = card.putObject("header").put("template", "blue");
        header.putObject("title").put("tag", "plain_text").put("content", "DEMO · " + businessName);
        header.putObject("icon").put("tag", "standard_icon").put("token", "bell_outlined");
        ObjectNode body = card.putObject("body").put("direction", "vertical")
                .put("padding", "12px 12px 20px 12px");
        body.putArray("elements").addObject().put("tag", "markdown")
                .put("content", "测试群：" + testChatId + "\nDEMO 消息，不代表正式事故通知。");
        return card.toString();
    }

    private static String mentions(com.fasterxml.jackson.databind.JsonNode openIds) {
        if (openIds == null || openIds.isEmpty()) return "未配置";
        StringBuilder result = new StringBuilder();
        for (var openId : openIds) {
            if (!result.isEmpty()) result.append(" ");
            result.append("<at id=").append(openId.asText()).append("></at>");
        }
        return result.toString();
    }

    private static ObjectNode details(ObjectNode incident) {
        return (ObjectNode) incident.path("details");
    }

    private static void metric(com.fasterxml.jackson.databind.node.ArrayNode columns,
                               String label, String value, String background) {
        ObjectNode column = columns.addObject().put("tag", "column")
                .put("width", "weighted").put("weight", 1)
                .put("background_style", background).put("corner_radius", "8px")
                .put("padding", "12px").put("vertical_spacing", "2px");
        column.putArray("elements").addObject().put("tag", "markdown")
                .put("content", "## " + value).put("text_align", "center");
        column.withArray("elements").addObject().put("tag", "markdown")
                .put("content", "<font color='grey'>" + label + "</font>")
                .put("text_align", "center").put("text_size", "notation");
    }

    private static void field(com.fasterxml.jackson.databind.node.ArrayNode fields,
                              String label, String value) {
        fields.addObject().put("is_short", true)
                .putObject("text").put("tag", "lark_md")
                .put("content", "**" + label + "**\n" + value);
    }

    private static void callback(ObjectNode button, ObjectNode incident, String action) {
        button.putArray("behaviors").addObject().put("type", "callback")
                .putObject("value").put("action", action)
                .put("incidentId", incident.path("incidentId").asText())
                .put("version", incident.path("version").asInt());
    }

    private static String tagColor(String phase) {
        return switch (phase) {
            case "RECOVERY" -> "green";
            case "REMINDER" -> "yellow";
            case "UPDATE" -> "orange";
            default -> "red";
        };
    }

    private static String template(String phase) {
        return switch (phase) {
            case "RECOVERY" -> "green";
            case "REMINDER" -> "yellow";
            case "UPDATE" -> "orange";
            default -> "red";
        };
    }
}
