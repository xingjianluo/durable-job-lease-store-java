package com.github.xingjianluo.durablejobleasestorejava.web.dto;

public record CompleteRequest(String workerId, String leaseToken,
                              Boolean succeeded, String errorMessage) {
}
