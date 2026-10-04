package com.example.ledgerflow.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class Pageables {

    private static final int MAX_PAGE_SIZE = 100;

    private Pageables() {
    }

    /**
     * Keeps the client's page number and (capped) size but ignores any client-supplied sort,
     * so an unknown sort property can never reach the query. Ordering matches the
     * {@code (account, created_at DESC)} indexes.
     */
    public static Pageable latestFirst(Pageable requested) {
        int size = Math.min(Math.max(requested.getPageSize(), 1), MAX_PAGE_SIZE);
        return PageRequest.of(requested.getPageNumber(), size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
