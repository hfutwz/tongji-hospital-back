package com.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MapPointDTO {
    private Integer patientId;
    private Double latitude;
    private Double longitude;
}

