package com.orderai.workbench.dto.request;

/** Carries the name and email used to create a customer. */
public record CreateCustomerRequest(String name, String email) {}
