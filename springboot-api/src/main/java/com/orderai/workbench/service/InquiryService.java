package com.orderai.workbench.service;

import com.orderai.workbench.dto.response.InquiryResponse;
import java.util.Map;

public interface InquiryService {
  InquiryResponse create(Map<String, String> payload, String key);
}
