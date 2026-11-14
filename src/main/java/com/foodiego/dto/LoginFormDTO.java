package com.foodiego.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
    private String portal;
}
