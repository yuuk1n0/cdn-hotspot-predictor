package com.yukino.cdn.operations;
import com.fasterxml.jackson.databind.node.ObjectNode;
public interface ModelEvidenceGateway {
    ObjectNode metrics(String appId, int horizonDays);
    default ObjectNode review(String appId, int horizonDays) { return metrics(appId, horizonDays); }
}
