package com.orderai.workbench.service;

import com.orderai.workbench.dto.request.RefundCreateRequest;
import com.orderai.workbench.dto.request.RefundReceiveRequest;
import com.orderai.workbench.dto.response.RefundResponse;

public interface RefundService {
  RefundResponse create(RefundCreateRequest payload, String key);

  RefundResponse find(long id);

  RefundResponse approve(long id);

  RefundResponse reject(long id);

  RefundResponse update(long id, String reason);

  void delete(long id);

  RefundResponse receive(long id, RefundReceiveRequest payload);
}
