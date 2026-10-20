package com.skala.chagok.domain.job.service;

public class JobCalculationException extends RuntimeException {
    private final String code;
    public JobCalculationException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
