package com.orderai.workbench.dto.request;

import java.util.List;
@SuppressWarnings("unused")
public record RefundReceiveRequest(List<RefundReceiveLineRequest> items) {}
