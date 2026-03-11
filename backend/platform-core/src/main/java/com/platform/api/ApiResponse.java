package com.platform.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        String error,
        Pagination pagination
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<List<T>> page(Page<T> page) {
        Pagination pagination = new Pagination(
                page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages()
        );
        return new ApiResponse<>(true, page.getContent(), null, pagination);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message, null);
    }

    public record Pagination(int page, int size, long totalElements, int totalPages) {}
}
