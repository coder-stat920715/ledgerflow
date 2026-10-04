package com.example.ledgerflow.mapper;

import com.example.ledgerflow.domain.entity.Account;
import com.example.ledgerflow.dto.response.AccountResponse;
import com.example.ledgerflow.dto.response.BalanceResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface AccountMapper {

    @Mapping(target = "ownerId", source = "owner.id")
    AccountResponse toResponse(Account account);

    @Mapping(target = "accountId", source = "id")
    @Mapping(target = "asOf", expression = "java(java.time.Instant.now())")
    BalanceResponse toBalanceResponse(Account account);
}
