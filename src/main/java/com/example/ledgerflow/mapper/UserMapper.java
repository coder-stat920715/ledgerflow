package com.example.ledgerflow.mapper;

import com.example.ledgerflow.domain.entity.User;
import com.example.ledgerflow.dto.response.UserResponse;
import org.mapstruct.Mapper;

@Mapper
public interface UserMapper {

    UserResponse toResponse(User user);
}
