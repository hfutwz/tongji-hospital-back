package com.demo.Service.impl.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.demo.Service.impl.IInjuryRecordService;
import com.demo.dto.AddressCountDTO;
import com.demo.dto.HourlyStatisticsDTO;
import com.demo.dto.HourlyGroupDTO;
import com.demo.dto.HourlyGroupStatisticsDTO;
import com.demo.entity.InjuryRecord;
import com.demo.mapper.InjuryRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class InjuryRecordImpl extends ServiceImpl<InjuryRecordMapper, InjuryRecord> implements IInjuryRecordService {
    @Autowired
    private InjuryRecordMapper injuryRecordMapper;

    @Override
    public List<AddressCountDTO> getAllLocations() {
        // 查询所有经纬度和病例数数据
        List<AddressCountDTO> allLocations = injuryRecordMapper.selectAllLocationCounts();
        // 过滤掉经纬度为null的记录
        return allLocations.stream()
                .filter(dto -> dto.getLatitude() != null && dto.getLongitude() != null)
                .collect(Collectors.toList());
    }
    @Override
    public List<AddressCountDTO> getLocationsByTimeRange(String startDate, String endDate, List<Integer> timePeriods) {
        return baseMapper.selectLocationsByTimeRange(startDate, endDate, timePeriods);
    }

    @Override
    public List<AddressCountDTO> getLocationsBySeasonsAndTime(List<Integer> seasons, List<Integer> timePeriods, List<Integer> years) {
        return baseMapper.selectLocationsBySeasonsAndTime(seasons, timePeriods, years);
    }

    @Override
    public List<HourlyStatisticsDTO> getHourlyStatistics(Integer year, List<Integer> seasons, String startDate, String endDate) {
        return baseMapper.selectHourlyStatistics(year, seasons, startDate, endDate);
    }

    @Override
    public List<HourlyGroupStatisticsDTO> getHourlyStatisticsByGroups(Integer year, List<Integer> seasons, String startDate, String endDate, List<HourlyGroupDTO> groups) {
        if (groups == null || groups.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<HourlyGroupStatisticsDTO> result = new ArrayList<>();
        
        for (HourlyGroupDTO group : groups) {
            if (group.getHours() == null || group.getHours().isEmpty()) {
                continue;
            }
            
            // 查询该组的患者总数
            Integer count = baseMapper.selectPatientCountByHours(year, seasons, startDate, endDate, group.getHours());
            if (count == null) {
                count = 0;
            }
            
            // 生成组标签（如 "0-1,1-2,2-3"）
            String groupLabel = group.getHours().stream()
                    .sorted()
                    .map(h -> {
                        int nextHour = (h + 1) % 24;
                        return String.format("%02d:00-%02d:00", h, nextHour);
                    })
                    .collect(Collectors.joining(","));
            
            // 生成小时显示（如 "0,1,2"）
            String hoursDisplay = group.getHours().stream()
                    .sorted()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
            
            HourlyGroupStatisticsDTO dto = new HourlyGroupStatisticsDTO();
            dto.setGroupIndex(group.getGroupIndex());
            dto.setGroupLabel(groupLabel);
            dto.setCount(count);
            dto.setHoursDisplay(hoursDisplay);
            
            result.add(dto);
        }
        
        return result;
    }

    @Override
    public List<Integer> getAvailableYears() {
        return baseMapper.selectAvailableYears();
    }

    @Override
    public String getInjuryLocationByPatientId(Integer patientId) {
        if (patientId == null) {
            return null;
        }
        // 查询该患者的受伤记录（一个患者可能有多条记录）
        // 优先返回有地址的记录，如果没有则返回最新的记录
        List<InjuryRecord> records = baseMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InjuryRecord>()
                .eq(InjuryRecord::getPatientId, patientId)
                .orderByDesc(InjuryRecord::getInjuryId) // 按ID降序，获取最新的记录
        );
        
        if (records == null || records.isEmpty()) {
            return null;
        }
        
        // 优先返回有地址的记录
        for (InjuryRecord record : records) {
            String location = record.getInjuryLocationDesc();
            if (location != null && !location.trim().isEmpty()) {
                return location;
            }
        }
        
        // 如果所有记录都没有地址，返回最新记录的地址（可能为null）
        return records.get(0).getInjuryLocationDesc();
    }

    @Override
    public boolean updateInjuryLocation(Integer patientId, String injuryLocation) {
        if (patientId == null) {
            return false;
        }
        // 更新该患者的所有受伤记录的地址
        InjuryRecord record = new InjuryRecord();
        record.setInjuryLocationDesc(injuryLocation);
        return baseMapper.update(record,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InjuryRecord>()
                .eq(InjuryRecord::getPatientId, patientId)
        ) > 0;
    }

}
