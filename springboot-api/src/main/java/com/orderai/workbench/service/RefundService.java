package com.orderai.workbench.service;

import com.orderai.workbench.dto.response.RefundResponse;
import java.util.List;
import java.util.Map;

public interface RefundService {
  RefundResponse create(Map<String, String> payload, String key);

  List<RefundResponse> list();

  RefundResponse approve(long id);
}
