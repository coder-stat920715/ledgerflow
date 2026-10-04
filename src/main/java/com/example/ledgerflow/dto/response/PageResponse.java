package com.example.ledgerflow.dto.response;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Stable JSON shape for paged results. Spring's {@code PageImpl} is not a supported
 * serialization contract, and these responses may be cached.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
