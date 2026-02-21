package com.demo.upload.validator;

import com.demo.upload.dto.ValidationErrorDTO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 干预时间字段验证器
 * 负责所有字段的格式验证逻辑
 */
@Component
public class InterventionTimeFieldValidator {
    
    /**
     * 日期格式：YYYY-MM-DD
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    /**
     * 日期格式正则表达式：严格匹配 YYYY-MM-DD
     */
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    
    /**
     * 4位时间格式正则表达式：严格匹配 0000-2359
     */
    private static final Pattern TIME_4_DIGIT_PATTERN = Pattern.compile("^\\d{4}$");
    
    /**
     * 匹配 有:〖数字〗 格式的正则表达式
     */
    private static final Pattern HAS_TIME_PATTERN = Pattern.compile("有:〖(\\d{4})〗");
    
    /**
     * 匹配 是:〖数字〗 格式的正则表达式
     */
    private static final Pattern YES_TIME_PATTERN = Pattern.compile("是:〖(\\d{4})〗");
    
    /**
     * 匹配 有，开始时间:〖数字〗 格式的正则表达式
     */
    private static final Pattern VENTILATOR_PATTERN = Pattern.compile("(?:有|是)[，,]?开始时间:?\\s*〖(\\d{4})〗");

    // ====================== 通用工具方法 ======================
    private String standardize(String raw) {
        if (raw == null) return "";
        return raw.replace("：", ":").replace("，", ",").trim();
    }

    private enum YesNoStatus { YES, NO, EMPTY, INVALID }


    private YesNoStatus normalizeYesNo(String raw) {
        if (raw == null || raw.trim().isEmpty()) return YesNoStatus.EMPTY;
        String v = standardize(raw);
        if ("是".equals(v) || "有".equals(v)) return YesNoStatus.YES;
        if ("否".equals(v) || "无".equals(v)) return YesNoStatus.NO;
        return YesNoStatus.INVALID;
    }

    private String validateTimeString(String timeStr, String fieldLabel, Object rawValue,
                                      int excelRowNumber, int patientId, List<ValidationErrorDTO> errors) {
        if (!TIME_4_DIGIT_PATTERN.matcher(timeStr).matches()) {
            errors.add(createValidationError(
                excelRowNumber, patientId, fieldLabel, rawValue,
                fieldLabel + "格式不正确: " + timeStr + "（必须是4位数字，例如 1100）"
            ));
            return null;
        }
        int hour = Integer.parseInt(timeStr.substring(0, 2));
        int minute = Integer.parseInt(timeStr.substring(2, 4));
        if (hour >= 24 || minute >= 60) {
            errors.add(createValidationError(
                excelRowNumber, patientId, fieldLabel, rawValue,
                fieldLabel + "时间超出范围: " + timeStr + "（小时必须在00-23之间，分钟必须在00-59之间）"
            ));
            return null;
        }
        return timeStr;
    }

    private String extractMarkedTime(String token, Object rawValue, String fieldLabel,
                                     int excelRowNumber, int patientId, List<ValidationErrorDTO> errors) {
        java.util.regex.Matcher m1 = HAS_TIME_PATTERN.matcher(token);
        if (m1.find()) {
            return validateTimeString(m1.group(1), fieldLabel, rawValue, excelRowNumber, patientId, errors);
        }
        java.util.regex.Matcher m2 = YES_TIME_PATTERN.matcher(token);
        if (m2.find()) {
            return validateTimeString(m2.group(1), fieldLabel, rawValue, excelRowNumber, patientId, errors);
        }
        return null;
    }

    /**
     * 除颤：仅存“次数”数字，不做时间校验
     * 规则：
     * - 无/否/空：返回null
     * - 是/有 + 数字：提取首个数字串；若心肺复苏开始时间为(跳过)，视为不合理，报错
     * - 是/有 无数字：
     *      若 cprStartTime == "(跳过)" -> 返回 null（视为否/无）
     *      否则报错（提示需提供次数）
     * - 其他：记录错误
     */
    public String validateDefibrillationField(Row row, Integer columnIndex, int excelRowNumber, int patientId,
                                              String fieldName, String cprStartTime, List<ValidationErrorDTO> errors) {
        if (row == null || columnIndex == null) {
            return null;
        }
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return null;
        }
        Object rawValue = getCellRawValue(row, columnIndex);
        String stringValue = standardize(getCellValueAsString(cell));
        if (stringValue.isEmpty()) {
            return null;
        }
        String stdCprStart = standardize(cprStartTime);
        boolean cprStartIsSkip = (cprStartTime == null || cprStartTime.trim().isEmpty() || "(跳过)".equals(stdCprStart));

        YesNoStatus status = normalizeYesNo(stringValue);
        if (status == YesNoStatus.NO || status == YesNoStatus.EMPTY) {
            return null;
        }
        if (status == YesNoStatus.YES || stringValue.startsWith("是") || stringValue.startsWith("有")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(stringValue);
            if (m.find()) {
                String num = m.group(1);
                if (cprStartIsSkip) {
                    errors.add(createValidationError(
                        excelRowNumber,
                        patientId,
                        fieldName,
                        rawValue,
                        fieldName + "已有次数(" + num + ")，但心肺复苏开始时间为(跳过)，请先填写心肺复苏开始时间"
                    ));
                    return null;
                }
                return num; // 仅存数字部分
            }
            if (cprStartIsSkip) {
                return null; // 视为否/无
            }
            errors.add(createValidationError(
                excelRowNumber,
                patientId,
                fieldName,
                rawValue,
                fieldName + "缺少次数，格式示例：是:3次（心肺复苏开始时间已填写时需提供次数）"
            ));
            return null;
        }
        errors.add(createValidationError(
            excelRowNumber,
            patientId,
            fieldName,
            rawValue,
            fieldName + "格式不正确: " + stringValue + "（正常示例：是:3次 / 无 / 否；全角/半角冒号均可）"
        ));
        return null;
    }

    /**
     * 导尿：是/有+时间；是/有无时间且尿量为0视为否；否则报错
     */
    public String validateCatheterField(Row row, Integer catheterCol, Integer urineCol, int excelRowNumber, int patientId,
                                        String fieldName, List<ValidationErrorDTO> errors) {
        if (row == null || catheterCol == null) {
            return null;
        }
        Cell cell = row.getCell(catheterCol);
        if (cell == null) {
            return null;
        }
        Object rawValue = getCellRawValue(row, catheterCol);
        String stringValue = standardize(getCellValueAsString(cell));
        if (stringValue.isEmpty()) {
            return "否";
        }
        YesNoStatus status = normalizeYesNo(stringValue);
        if (status == YesNoStatus.NO || status == YesNoStatus.EMPTY) {
            return "否";
        }
        // 尝试提取时间
        java.util.regex.Matcher matcher = YES_TIME_PATTERN.matcher(stringValue);
        if (matcher.find()) {
            String timeStr = matcher.group(1);
            return validateTimeString(timeStr, fieldName, rawValue, excelRowNumber, patientId, errors);
        }
        // 是/有但无时间
        if (status == YesNoStatus.YES || stringValue.startsWith("是:") || stringValue.startsWith("有:")) {
            boolean urineZero = false;
            if (urineCol != null) {
                Cell urineCell = row.getCell(urineCol);
                String urineRaw = standardize(getCellValueAsString(urineCell));
                java.util.regex.Matcher um = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(urineRaw);
                if (um.find()) {
                    try {
                        double v = Double.parseDouble(um.group(1));
                        urineZero = (Math.abs(v) < 1e-9);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (urineZero) {
                return "否";
            }
            errors.add(createValidationError(
                excelRowNumber,
                patientId,
                fieldName,
                rawValue,
                fieldName + "缺少时间（尿量不为0时需提供导尿时间）"
            ));
            return null;
        }
        // 非法格式
        errors.add(createValidationError(
            excelRowNumber,
            patientId,
            fieldName,
            rawValue,
            fieldName + "格式不正确: " + stringValue + "（正常格式：是/有:〖0002〗、否/无，括号内必须是4位数字；全角/半角冒号均可）"
        ));
        return null;
    }
    
    /**
     * 验证接诊日期（复用 InjuryRecordFieldValidator 的逻辑）
     */
    public LocalDate validateAdmissionDate(Row row, Integer columnIndex, int excelRowNumber, int patientId, List<ValidationErrorDTO> errors) {
        if (row == null || columnIndex == null) {
            errors.add(createValidationError(
                excelRowNumber,
                patientId,
                "接诊日期",
                "",
                "接诊日期不能为空"
            ));
            return null;
        }
        
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            errors.add(createValidationError(
                excelRowNumber,
                patientId,
                "接诊日期",
                "",
                "接诊日期不能为空"
            ));
            return null;
        }
        
        Object rawValue = getCellRawValue(row, columnIndex);
        LocalDate admissionDate = null;
        
        try {
            String stringValue = null;
            
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                stringValue = cell.getStringCellValue().trim();
            } else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                if (DateUtil.isCellDateFormatted(cell)) {
                    try {
                        java.util.Date dateValue = cell.getDateCellValue();
                        admissionDate = dateValue.toInstant()
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate();
                        String formattedDate = admissionDate.format(DATE_FORMATTER);
                        if (!DATE_PATTERN.matcher(formattedDate).matches()) {
                            errors.add(createValidationError(
                                excelRowNumber,
                                patientId,
                                "接诊日期",
                                rawValue,
                                "接诊日期格式不正确: " + formattedDate + "（必须是 YYYY-MM-DD 格式，例如 2024-10-29）"
                            ));
                            return null;
                        }
                        return admissionDate;
                    } catch (Exception e) {
                        errors.add(createValidationError(
                            excelRowNumber,
                            patientId,
                            "接诊日期",
                            rawValue,
                            "接诊日期格式不正确: " + (rawValue != null ? rawValue.toString() : "") + "（必须是 YYYY-MM-DD 格式，例如 2024-10-29）"
                        ));
                        return null;
                    }
                } else {
                    stringValue = String.valueOf((long) cell.getNumericCellValue());
                }
            }
            
            if (stringValue == null || stringValue.isEmpty()) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    "接诊日期",
                    rawValue,
                    "接诊日期不能为空"
                ));
                return null;
            }
            
            if (!DATE_PATTERN.matcher(stringValue).matches()) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    "接诊日期",
                    rawValue,
                    "接诊日期格式不正确: " + stringValue + "（必须是 YYYY-MM-DD 格式，例如 2024-10-29）"
                ));
                return null;
            }
            
            try {
                admissionDate = LocalDate.parse(stringValue, DATE_FORMATTER);
            } catch (DateTimeParseException e) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    "接诊日期",
                    rawValue,
                    "接诊日期格式不正确: " + stringValue + "（必须是 YYYY-MM-DD 格式，例如 2024-10-29）"
                ));
                return null;
            }
            
        } catch (Exception e) {
            errors.add(createValidationError(
                excelRowNumber,
                patientId,
                "接诊日期",
                rawValue,
                "接诊日期格式不正确: " + (rawValue != null ? rawValue.toString() : "") + "（必须是 YYYY-MM-DD 格式，例如 2024-10-29）"
            ));
            return null;
        }
        
        return admissionDate;
    }
    
    /**
     * 验证时间（4位数字，00-23点，00-59分），可自定义字段名
     */
    public String validateAdmissionTime(Row row, Integer columnIndex, int excelRowNumber, int patientId, String fieldLabel, List<ValidationErrorDTO> errors) {
        if (row == null || columnIndex == null) {
            errors.add(createValidationError(
                excelRowNumber,
                patientId,
                fieldLabel,
                "",
                fieldLabel + "不能为空"
            ));
            return null;
        }
        
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            errors.add(createValidationError(
                excelRowNumber,
                patientId,
                fieldLabel,
                "",
                fieldLabel + "不能为空"
            ));
            return null;
        }
        
        Object rawValue = getCellRawValue(row, columnIndex);
        String admissionTime = null;
        
        try {
            String stringValue = null;
            
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                stringValue = cell.getStringCellValue().trim();
            } else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                double numericValue = cell.getNumericCellValue();
                if (numericValue == (long) numericValue) {
                    stringValue = String.format("%04d", (long) numericValue);
                } else {
                    stringValue = String.valueOf(numericValue);
                }
            }
            
            if (stringValue == null || stringValue.isEmpty()) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    fieldLabel,
                    rawValue,
                    fieldLabel + "不能为空"
                ));
                return null;
            }
            
            // 验证是否为4位数字
            if (!TIME_4_DIGIT_PATTERN.matcher(stringValue).matches()) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    fieldLabel,
                    rawValue,
                    fieldLabel + "格式不正确: " + stringValue + "（必须是4位数字，例如 1100）"
                ));
                return null;
            }
            
            // 验证时间范围（0000-2359）
            int hour = Integer.parseInt(stringValue.substring(0, 2));
            int minute = Integer.parseInt(stringValue.substring(2, 4));
            if (hour >= 24 || minute >= 60) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    fieldLabel,
                    rawValue,
                    fieldLabel + "超出范围: " + stringValue + "（小时必须在00-23之间，分钟必须在00-59之间）"
                ));
                return null;
            }
            
            admissionTime = stringValue;
            
        } catch (Exception e) {
            errors.add(createValidationError(
                excelRowNumber,
                patientId,
                fieldLabel,
                rawValue,
                fieldLabel + "格式不正确: " + (rawValue != null ? rawValue.toString() : "") + "（必须是4位数字，例如 1100）"
            ));
            return null;
        }
        
        return admissionTime;
    }
    
    /**
     * 兼容旧调用，默认字段名“接诊时间”
     */
    public String validateAdmissionTime(Row row, Integer columnIndex, int excelRowNumber, int patientId, List<ValidationErrorDTO> errors) {
        return validateAdmissionTime(row, columnIndex, excelRowNumber, patientId, "接诊时间", errors);
    }
    
    /**
     * 验证时间值字段（格式：无、有:〖0958〗）
     * 特殊：若字符串以“有:”开头但没有时间，且提供fallbackTime，则返回fallbackTime
     * 
     * @param row Excel行
     * @param columnIndex 列索引
     * @param excelRowNumber Excel行号
     * @param patientId 患者ID
     * @param fieldName 字段名称（用于错误提示）
     * @param fallbackTime 当“有:”但缺少时间时使用的回退时间（如入室时间），可为null
     * @param errors 错误列表
     * @return 验证后的时间值（4位数字字符串），如果无效或为空返回null
     */
    public String validateTimeValueField(Row row, Integer columnIndex, int excelRowNumber, int patientId, String fieldName, String fallbackTime, List<ValidationErrorDTO> errors) {
        if (row == null || columnIndex == null) {
            return null;
        }
        
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return null;
        }
        
        Object rawValue = getCellRawValue(row, columnIndex);
        String stringValue = standardize(getCellValueAsString(cell));
        
        if (stringValue.isEmpty()) {
            return null;
        }
        
        YesNoStatus status = normalizeYesNo(stringValue);
        if (status == YesNoStatus.NO) {
            return null;
        }

        String time = extractMarkedTime(stringValue, rawValue, fieldName, excelRowNumber, patientId, errors);
        if (time != null) {
            return time;
        }

        // 如果标记为有/是但无时间，尝试回退
        if (status == YesNoStatus.YES || stringValue.startsWith("有:") || stringValue.startsWith("是:")) {
            if (fallbackTime != null && !fallbackTime.trim().isEmpty()) {
                return fallbackTime.trim();
            }
            return null;
        }

        // 其他情况：非法
        errors.add(createValidationError(
            excelRowNumber,
            patientId,
            fieldName,
            rawValue,
            fieldName + "格式不正确: " + stringValue + "（正常格式：无/否 或 有:/是:〖0958〗，括号内必须是4位数字；全角/半角冒号均可）"
        ));
        return null;
    }
    
    /**
     * 兼容旧调用（无回退时间）
     */
    public String validateTimeValueField(Row row, Integer columnIndex, int excelRowNumber, int patientId, String fieldName, List<ValidationErrorDTO> errors) {
        return validateTimeValueField(row, columnIndex, excelRowNumber, patientId, fieldName, null, errors);
    }
    
    /**
     * 验证呼吸机字段（格式：无、有，开始时间:〖0910〗）
     * 
     * @param row Excel行
     * @param columnIndex 列索引
     * @param excelRowNumber Excel行号
     * @param patientId 患者ID
     * @param errors 错误列表
     * @return 验证后的时间值（4位数字字符串），如果无效或为空返回null
     */
    public String validateVentilatorField(Row row, Integer columnIndex, int excelRowNumber, int patientId, List<ValidationErrorDTO> errors) {
        if (row == null || columnIndex == null) {
            return null;
        }
        
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return null;
        }
        
        Object rawValue = getCellRawValue(row, columnIndex);
        String stringValue = getCellValueAsString(cell);
        
        if (stringValue == null || stringValue.trim().isEmpty()) {
            return null;
        }
        
        stringValue = standardize(stringValue);
        YesNoStatus status = normalizeYesNo(stringValue);
        if (status == YesNoStatus.NO || status == YesNoStatus.EMPTY) {
            return null;
        }
        
        // 匹配 有，开始时间:〖数字〗 格式
        java.util.regex.Matcher matcher = VENTILATOR_PATTERN.matcher(stringValue);
        if (matcher.find()) {
            String timeStr = matcher.group(1);
            // 验证是否为4位数字
            if (!TIME_4_DIGIT_PATTERN.matcher(timeStr).matches()) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    "呼吸机",
                    rawValue,
                    "呼吸机格式不正确: " + stringValue + "（括号内必须是4位数字，例如 有，开始时间:〖0910〗）"
                ));
                return null;
            }
            
            // 验证时间范围（0000-2359）
            int hour = Integer.parseInt(timeStr.substring(0, 2));
            int minute = Integer.parseInt(timeStr.substring(2, 4));
            if (hour >= 24 || minute >= 60) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    "呼吸机",
                    rawValue,
                    "呼吸机时间超出范围: " + timeStr + "（小时必须在00-23之间，分钟必须在00-59之间）"
                ));
                return null;
            }
            
            return timeStr;
        }
        
        // 是/有 但无时间，按无处理
        if (status == YesNoStatus.YES || stringValue.startsWith("有") || stringValue.startsWith("是")) {
            return null;
        }

        // 如果格式不匹配，记录错误
        errors.add(createValidationError(
            excelRowNumber,
            patientId,
            "呼吸机",
            rawValue,
            "呼吸机格式不正确: " + stringValue + "（正常格式：无/否，或有/是，开始时间:〖xxxx〗，括号内必须是4位数字；全角/半角冒号均可）"
        ));
        return null;
    }
    
    /**
     * 验证是/否字段（格式：是/否）
     * 
     * @param row Excel行
     * @param columnIndex 列索引
     * @param excelRowNumber Excel行号
     * @param patientId 患者ID
     * @param fieldName 字段名称
     * @param errors 错误列表
     * @return 验证后的值（"是"或"否"），如果无效返回null
     */
    public String validateYesNoField(Row row, Integer columnIndex, int excelRowNumber, int patientId, String fieldName, List<ValidationErrorDTO> errors) {
        if (row == null || columnIndex == null) {
            return null;
        }
        
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return null;
        }
        
        Object rawValue = getCellRawValue(row, columnIndex);
        String stringValue = getCellValueAsString(cell);
        
        if (stringValue == null || stringValue.trim().isEmpty()) {
            return null;
        }
        
        // 标准化：全角冒号转半角，去掉多余空格
        stringValue = standardize(stringValue);
        
        // 同义词处理
        if ("是".equals(stringValue) || "有".equals(stringValue)) {
            return "是";
        } else if ("否".equals(stringValue) || "无".equals(stringValue)) {
            return "否";
        } else {
            errors.add(createValidationError(
                excelRowNumber,
                patientId,
                fieldName,
                rawValue,
                fieldName + "格式不正确: " + stringValue + "（只能是\"是/有\"或\"否/无\"）"
            ));
            return null;
        }
    }
    
    /**
     * 验证4位时间字段（格式：1600 或 (跳过)）
     * 
     * @param row Excel行
     * @param columnIndex 列索引
     * @param excelRowNumber Excel行号
     * @param patientId 患者ID
     * @param fieldName 字段名称
     * @param errors 错误列表
     * @return 验证后的时间值（4位数字字符串），如果无效或为(跳过)返回null
     */
    public String validate4DigitTimeField(Row row, Integer columnIndex, int excelRowNumber, int patientId, String fieldName, List<ValidationErrorDTO> errors) {
        if (row == null || columnIndex == null) {
            return null;
        }
        
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return null;
        }
        
        Object rawValue = getCellRawValue(row, columnIndex);
        String stringValue = getCellValueAsString(cell);
        
        if (stringValue == null || stringValue.trim().isEmpty()) {
            return null;
        }
        
        // 标准化：全角冒号转半角，去掉多余空格
        stringValue = stringValue.replace("：", ":").trim();
        
        // 如果是"(跳过)"，返回null
        if ("(跳过)".equals(stringValue)) {
            return null;
        }
        
        // 验证是否为4位数字
        if (!TIME_4_DIGIT_PATTERN.matcher(stringValue).matches()) {
            errors.add(createValidationError(
                excelRowNumber,
                patientId,
                fieldName,
                rawValue,
                fieldName + "格式不正确: " + stringValue + "（必须是4位数字，例如 1600）或(跳过)"
            ));
            return null;
        }
        
        // 验证时间范围（0000-2359）
        int hour = Integer.parseInt(stringValue.substring(0, 2));
        int minute = Integer.parseInt(stringValue.substring(2, 4));
        if (hour >= 24 || minute >= 60) {
            errors.add(createValidationError(
                excelRowNumber,
                patientId,
                fieldName,
                rawValue,
                fieldName + "时间超出范围: " + stringValue + "（小时必须在00-23之间，分钟必须在00-59之间）"
            ));
            return null;
        }
        
        return stringValue;
    }
    
    /**
     * 验证是/否时间字段（格式：是:〖0002〗、否）
     * 对于采血和CT字段：如果为"是:"但没有数字，视作"否"，不记录错误
     * 
     * @param row Excel行
     * @param columnIndex 列索引
     * @param excelRowNumber Excel行号
     * @param patientId 患者ID
     * @param fieldName 字段名称
     * @param errors 错误列表
     * @return 验证后的时间值（4位数字字符串），如果无效或为空返回null
     */
    public String validateYesNoTimeField(Row row, Integer columnIndex, int excelRowNumber, int patientId, String fieldName, String fallbackYesNoValue, List<ValidationErrorDTO> errors) {
        if (row == null || columnIndex == null) {
            return null;
        }
        
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return null;
        }
        
        Object rawValue = getCellRawValue(row, columnIndex);
        String stringValue = standardize(getCellValueAsString(cell));
        
        if (stringValue.isEmpty()) {
            return fallbackYesNoValue == null ? "否" : fallbackYesNoValue;
        }
        
        YesNoStatus status = normalizeYesNo(stringValue);
        if (status == YesNoStatus.NO || status == YesNoStatus.EMPTY) {
            return fallbackYesNoValue == null ? "否" : fallbackYesNoValue;
        }
        
        // 提取时间
        java.util.regex.Matcher matcher = YES_TIME_PATTERN.matcher(stringValue);
        if (matcher.find()) {
            String timeStr = matcher.group(1);
            return validateTimeString(timeStr, fieldName, rawValue, excelRowNumber, patientId, errors);
        }
        
        // 是/有但无时间：返回 fallback（默认否），不报错
        if (status == YesNoStatus.YES || stringValue.startsWith("是:") || stringValue.startsWith("有:")) {
            return fallbackYesNoValue == null ? "否" : fallbackYesNoValue;
        }
        
        // 非法格式
        errors.add(createValidationError(
            excelRowNumber,
            patientId,
            fieldName,
            rawValue,
            fieldName + "格式不正确: " + stringValue + "（正常格式：是/有:〖0002〗、否/无，括号内必须是4位数字；全角/半角冒号均可）"
        ));
        return null;
    }
    
    // 兼容旧调用（默认无时间时返回“否”）
    public String validateYesNoTimeField(Row row, Integer columnIndex, int excelRowNumber, int patientId, String fieldName, List<ValidationErrorDTO> errors) {
        return validateYesNoTimeField(row, columnIndex, excelRowNumber, patientId, fieldName, "否", errors);
    }

    // 内部复用：返回结果与“是/有但无时间”标记
    
    /**
     * 验证死亡日期（格式：YYYY-MM-DD 或 (跳过)）
     * 
     * @param row Excel行
     * @param columnIndex 列索引
     * @param excelRowNumber Excel行号
     * @param patientId 患者ID
     * @param errors 错误列表
     * @return 验证后的日期，如果无效或为(跳过)返回null
     */
    public LocalDate validateDeathDate(Row row, Integer columnIndex, int excelRowNumber, int patientId, List<ValidationErrorDTO> errors) {
        if (row == null || columnIndex == null) {
            return null;
        }
        
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return null;
        }
        
        Object rawValue = getCellRawValue(row, columnIndex);
        LocalDate deathDate = null;
        
        try {
            String stringValue = null;
            
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                stringValue = cell.getStringCellValue().trim();
            } else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                if (DateUtil.isCellDateFormatted(cell)) {
                    try {
                        java.util.Date dateValue = cell.getDateCellValue();
                        deathDate = dateValue.toInstant()
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate();
                        String formattedDate = deathDate.format(DATE_FORMATTER);
                        if (!DATE_PATTERN.matcher(formattedDate).matches()) {
                            errors.add(createValidationError(
                                excelRowNumber,
                                patientId,
                                "死亡日期",
                                rawValue,
                                "死亡日期格式不正确: " + formattedDate + "（必须是 YYYY-MM-DD 格式，例如 2024-10-29）或(跳过)"
                            ));
                            return null;
                        }
                        return deathDate;
                    } catch (Exception e) {
                        errors.add(createValidationError(
                            excelRowNumber,
                            patientId,
                            "死亡日期",
                            rawValue,
                            "死亡日期格式不正确: " + (rawValue != null ? rawValue.toString() : "") + "（必须是 YYYY-MM-DD 格式，例如 2024-10-29）或(跳过)"
                        ));
                        return null;
                    }
                } else {
                    stringValue = String.valueOf((long) cell.getNumericCellValue());
                }
            }
            
            if (stringValue == null || stringValue.isEmpty()) {
                return null; // 死亡日期可以为空
            }
            
            // 如果是"(跳过)"，返回null
            if ("(跳过)".equals(stringValue)) {
                return null;
            }
            
            if (!DATE_PATTERN.matcher(stringValue).matches()) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    "死亡日期",
                    rawValue,
                    "死亡日期格式不正确: " + stringValue + "（必须是 YYYY-MM-DD 格式，例如 2024-10-29）或(跳过)"
                ));
                return null;
            }
            
            try {
                deathDate = LocalDate.parse(stringValue, DATE_FORMATTER);
            } catch (DateTimeParseException e) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    "死亡日期",
                    rawValue,
                    "死亡日期格式不正确: " + stringValue + "（必须是 YYYY-MM-DD 格式，例如 2024-10-29）或(跳过)"
                ));
                return null;
            }
            
        } catch (Exception e) {
            errors.add(createValidationError(
                excelRowNumber,
                patientId,
                "死亡日期",
                rawValue,
                "死亡日期格式不正确: " + (rawValue != null ? rawValue.toString() : "") + "（必须是 YYYY-MM-DD 格式，例如 2024-10-29）或(跳过)"
            ));
            return null;
        }
        
        return deathDate;
    }
    
    /**
     * 解析离开抢救室时间（支持 MM-DD HHMM、MM-D HHMM 和 HHMM 格式）
     * 
     * @param row Excel行
     * @param columnIndex 列索引
     * @param excelRowNumber Excel行号
     * @param patientId 患者ID
     * @param admissionDate 接诊日期
     * @param admissionTime 接诊时间
     * @param errors 错误列表
     * @return 返回数组 [leaveDate, leaveTime]，如果无效返回 [null, null]
     */
    public Object[] parseLeaveSurgeryTime(Row row, Integer columnIndex, int excelRowNumber, int patientId, LocalDate admissionDate, String admissionTime, List<ValidationErrorDTO> errors) {
        if (row == null || columnIndex == null) {
            return new Object[]{null, null};
        }
        
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return new Object[]{null, null};
        }
        
        Object rawValue = getCellRawValue(row, columnIndex);
        String stringValue = getCellValueAsString(cell);
        
        if (stringValue == null || stringValue.trim().isEmpty()) {
            return new Object[]{null, null};
        }
        
        stringValue = stringValue.trim();
        
        // 匹配 "MM-DD HHMM" 或 "MM-D HHMM" 格式（支持月份后跟1-2位日期）
        Pattern dateTimePattern = Pattern.compile("(\\d{2}-\\d{1,2})\\s+(\\d{4})");
        java.util.regex.Matcher dateTimeMatcher = dateTimePattern.matcher(stringValue);
        if (dateTimeMatcher.find()) {
            String monthDay = dateTimeMatcher.group(1);
            String timeStr = dateTimeMatcher.group(2);
            
            // 验证时间格式
            if (!TIME_4_DIGIT_PATTERN.matcher(timeStr).matches()) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    "离开抢救室时间",
                    rawValue,
                    "离开抢救室时间格式不正确: " + timeStr + "（必须是4位数字）"
                ));
                return new Object[]{null, null};
            }
            
            // 验证时间范围
            int hour = Integer.parseInt(timeStr.substring(0, 2));
            int minute = Integer.parseInt(timeStr.substring(2, 4));
            if (hour >= 24 || minute >= 60) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    "离开抢救室时间",
                    rawValue,
                    "离开抢救室时间超出范围: " + timeStr + "（小时必须在00-23之间，分钟必须在00-59之间）"
                ));
                return new Object[]{null, null};
            }
            
            try {
                // 处理日期格式：如果是 MM-D 格式，需要补零为 MM-0D
                String normalizedMonthDay = monthDay;
                if (monthDay.matches("\\d{2}-\\d{1}$")) {
                    // 格式为 MM-D，需要补零为 MM-0D
                    String[] parts = monthDay.split("-");
                    normalizedMonthDay = parts[0] + "-0" + parts[1];
                }
                
                int currentYear = admissionDate != null ? admissionDate.getYear() : java.time.LocalDate.now().getYear();
                LocalDate leaveDate = LocalDate.parse(currentYear + "-" + normalizedMonthDay, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                if (admissionDate != null && leaveDate.isBefore(admissionDate)) {
                    leaveDate = leaveDate.plusYears(1);
                }
                return new Object[]{leaveDate, timeStr};
            } catch (Exception e) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    "离开抢救室时间",
                    rawValue,
                    "离开抢救室日期格式不正确: " + monthDay
                ));
                return new Object[]{null, timeStr};
            }
        }
        
        // 匹配 "HHMM" 格式
        Pattern timePattern = Pattern.compile("^(\\d{4})$");
        java.util.regex.Matcher timeMatcher = timePattern.matcher(stringValue);
        if (timeMatcher.find()) {
            String timeStr = timeMatcher.group(1);
            
            // 验证时间格式
            if (!TIME_4_DIGIT_PATTERN.matcher(timeStr).matches()) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    "离开抢救室时间",
                    rawValue,
                    "离开抢救室时间格式不正确: " + timeStr + "（必须是4位数字）"
                ));
                return new Object[]{null, null};
            }
            
            // 验证时间范围
            int hour = Integer.parseInt(timeStr.substring(0, 2));
            int minute = Integer.parseInt(timeStr.substring(2, 4));
            if (hour >= 24 || minute >= 60) {
                errors.add(createValidationError(
                    excelRowNumber,
                    patientId,
                    "离开抢救室时间",
                    rawValue,
                    "离开抢救室时间超出范围: " + timeStr + "（小时必须在00-23之间，分钟必须在00-59之间）"
                ));
                return new Object[]{null, null};
            }
            
            // 如果接诊日期和时间都存在，判断日期
            if (admissionDate != null && admissionTime != null) {
                try {
                    int admissionHour = Integer.parseInt(admissionTime.substring(0, 2));
                    int admissionMinute = Integer.parseInt(admissionTime.substring(2, 4));
                    int leaveHour = Integer.parseInt(timeStr.substring(0, 2));
                    int leaveMinute = Integer.parseInt(timeStr.substring(2, 4));
                    
                    java.time.LocalTime admissionTimeObj = java.time.LocalTime.of(admissionHour, admissionMinute);
                    java.time.LocalTime leaveTimeObj = java.time.LocalTime.of(leaveHour, leaveMinute);
                    
                    if (leaveTimeObj.isAfter(admissionTimeObj) || leaveTimeObj.equals(admissionTimeObj)) {
                        return new Object[]{admissionDate, timeStr};
                    } else {
                        return new Object[]{admissionDate.plusDays(1), timeStr};
                    }
                } catch (Exception e) {
                    return new Object[]{admissionDate, timeStr};
                }
            }
            
            return new Object[]{admissionDate, timeStr};
        }
        
        // 格式不匹配，不记录错误，直接返回null（用户要求：格式不正确的数据不记录）
        return new Object[]{null, null};
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 获取单元格的字符串值
     */
    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return null;
        }
        
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    double numericValue = cell.getNumericCellValue();
                    if (numericValue == (long) numericValue) {
                        return String.format("%04d", (long) numericValue);
                    } else {
                        return String.valueOf(numericValue);
                    }
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        return String.valueOf((long) cell.getNumericCellValue());
                    } catch (Exception ex) {
                        return cell.getCellFormula();
                    }
                }
            case BLANK:
                return null;
            default:
                return null;
        }
    }
    
    /**
     * 获取单元格的原始值（用于错误报告）
     */
    private Object getCellRawValue(Row row, Integer columnIndex) {
        if (row == null || columnIndex == null) {
            return "";
        }
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return "";
        }
        
        try {
            switch (cell.getCellType()) {
                case STRING:
                    return cell.getStringCellValue();
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        return cell.getDateCellValue().toString();
                    } else {
                        double numericValue = cell.getNumericCellValue();
                        if (numericValue == (long) numericValue) {
                            return (long) numericValue;
                        } else {
                            return numericValue;
                        }
                    }
                case BOOLEAN:
                    return cell.getBooleanCellValue();
                case FORMULA:
                    try {
                        return cell.getStringCellValue();
                    } catch (Exception e) {
                        try {
                            return cell.getNumericCellValue();
                        } catch (Exception ex) {
                            return cell.getCellFormula();
                        }
                    }
                case BLANK:
                    return "";
                default:
                    return "";
            }
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * 创建验证错误对象
     */
    private ValidationErrorDTO createValidationError(int row, int patientId, String field, Object value, String message) {
        ValidationErrorDTO error = new ValidationErrorDTO();
        error.setRow(row);
        error.setPatientId(patientId);
        error.setField(field);
        error.setValue(value);
        error.setMessage(message);
        return error;
    }
}

