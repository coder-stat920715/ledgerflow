package com.example.ledgerflow.mapper;

import com.example.ledgerflow.domain.entity.LedgerEntry;
import com.example.ledgerflow.dto.response.LedgerEntryResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface LedgerEntryMapper {

    @Mapping(target = "transactionId", source = "transaction.id")
    @Mapping(target = "accountId", source = "account.id")
    LedgerEntryResponse toResponse(LedgerEntry entry);
}
