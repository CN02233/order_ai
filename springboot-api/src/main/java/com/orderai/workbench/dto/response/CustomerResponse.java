package com.orderai.workbench.dto.response;

/** Represents the customer data returned by the workbench API. */
public record CustomerResponse(Long id, String email, String name) {}
