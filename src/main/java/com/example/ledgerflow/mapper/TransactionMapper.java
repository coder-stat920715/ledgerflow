package com.example.ledgerflow.mapper;

import com.example.ledgerflow.domain.entity.Transaction;
import com.example.ledgerflow.dto.command.TransferCommand;
import com.example.ledgerflow.dto.request.TransferRequest;
import com.example.ledgerflow.dto.response.TransactionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface TransactionMapper {

    @Mapping(target = "sourceAccountId", source = "sourceAccount.id")
    @Mapping(target = "destinationAccountId", source = "destinationAccount.id")
    @Mapping(target = "reversalOfId", source = "reversalOf.id")
    TransactionResponse toResponse(Transaction transaction);

    default TransferCommand toCommand(TransferRequest request, String idempotencyKey) {
        return new TransferCommand(
                request.sourceAccountId(),
                request.destinationAccountId(),
                request.amount(),
                request.currency(),
                request.description(),
                idempotencyKey);
    }
}
