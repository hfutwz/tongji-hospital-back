package com.demo.upload.service;

import com.demo.upload.dto.ImportResultDTO;
import com.demo.upload.dto.ValidationErrorDTO;
import com.demo.upload.dto.ValidationResultDTO;
import com.demo.upload.exception.DataValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 综合数据导入服务
 * 整合所有9张表的验证和导入逻辑
 * 先验证所有表的数据，收集所有错误，只有全部通过才插入数据库
 */
@Service
public class ComprehensiveDataImportService {

    private static final Logger logger = LoggerFactory.getLogger(ComprehensiveDataImportService.class);

    /**
     * 最大错误信息数量（超过此数量后只保留部分错误，避免内存溢出）
     */
    private static final int MAX_ERRORS_IN_MEMORY = 10000;

    @Autowired
    private PatientDataImportService patientDataImportService;

    @Autowired
    private InjuryRecordImportService injuryRecordImportService;

    @Autowired
    private GcsScoreImportService gcsScoreImportService;

    @Autowired
    private RtsScoreImportService rtsScoreImportService;

    @Autowired
    private PatientInfoOnAdmissionImportService patientInfoOnAdmissionImportService;

    @Autowired
    private PatientInfoOffAdmissionImportService patientInfoOffAdmissionImportService;

    @Autowired
    private InterventionTimeImportService interventionTimeImportService;

    @Autowired
    private InterventionExtraImportService interventionExtraImportService;

    @Autowired
    private IssPatientInjurySeverityImportService issPatientInjurySeverityImportService;

    /**
     * 表信息配置
     */
    private static class TableInfo {
        String name;
        String label;
        ImportService service;

        TableInfo(String name, String label, ImportService service) {
            this.name = name;
            this.label = label;
            this.service = service;
        }
    }

    /**
     * 导入服务接口
     */
    private interface ImportService {
        Map<String, Object> validateAndImport(String excelFilePath);
    }

    /**
     * 批量验证并导入所有表的数据
     * 1. 先导入患者基本信息表（patient），确保所有患者信息先插入数据库
     * 2. 然后导入其他表，此时患者信息已存在，外键约束不会报错
     * 3. 收集所有错误
     * 4. 如果全部通过，数据已插入数据库；如果失败，事务回滚
     *
     * @param excelFilePath Excel文件路径
     * @return 包含所有表验证和导入结果的Map
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> validateAndImportAllTables(String excelFilePath) {
        logger.info("开始批量导入所有表的数据，文件路径: {}", excelFilePath);

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> tableResults = new LinkedHashMap<>();

        // 第一步：先导入患者基本信息表（patient），确保所有患者信息先插入数据库
        logger.info("第一步：先导入患者基本信息表");
        Map<String, Object> patientTableResult = new HashMap<>();
        try {
            Map<String, Object> patientImportResult = patientDataImportService.validateAndImportPatientData(excelFilePath);

            ValidationResultDTO patientValidation = (ValidationResultDTO) patientImportResult.get("validation");
            ImportResultDTO patientImport = (ImportResultDTO) patientImportResult.get("import");
            Boolean patientSuccess = (Boolean) patientImportResult.get("success");

            if (patientValidation == null) {
                patientValidation = new ValidationResultDTO();
                patientValidation.setSuccess(false);
                patientValidation.setValid(false);
                patientValidation.setErrorCount(1);
                patientValidation.setMessage("验证失败：无法获取验证结果");
                patientValidation.setErrors(new ArrayList<>());
            }

            boolean patientValid = patientValidation.getValid() != null && patientValidation.getValid();
            int patientErrorCount = patientValidation.getErrorCount() != null ? patientValidation.getErrorCount() : 0;

            patientTableResult.put("tableName", "patient");
            patientTableResult.put("tableLabel", "患者基本信息");
            patientTableResult.put("validation", patientValidation);
            patientTableResult.put("import", patientImport);
            patientTableResult.put("success", patientSuccess);
            patientTableResult.put("valid", patientValid);
            patientTableResult.put("errorCount", patientErrorCount);

            tableResults.put("patient", patientTableResult);

            logger.info("患者基本信息表导入完成: valid={}, errorCount={}, success={}",
                    patientValid, patientErrorCount, patientImport != null && patientImport.getSuccess());

            // 如果患者表导入失败，直接返回错误
            if (!patientValid || patientErrorCount > 0 || patientImport == null || !patientImport.getSuccess()) {
                logger.error("患者基本信息表导入失败，无法继续导入其他表");

                List<ValidationErrorDTO> allErrors = new ArrayList<>();
                if (patientValidation.getErrors() != null) {
                    for (ValidationErrorDTO error : patientValidation.getErrors()) {
                        if (allErrors.size() < MAX_ERRORS_IN_MEMORY) {
                            String originalMessage = error.getMessage() != null ? error.getMessage() : "";
                            error.setMessage("[患者基本信息] " + originalMessage);
                            allErrors.add(error);
                        }
                    }
                }

                result.put("success", false);
                result.put("allValid", false);
                result.put("totalErrorCount", patientErrorCount);
                result.put("allErrors", allErrors);
                result.put("tables", tableResults);
                result.put("message", String.format("患者基本信息导入失败，共发现 %d 个错误，无法继续导入其他表", patientErrorCount));

                throw new DataValidationException(
                        "患者基本信息导入失败，共发现 " + patientErrorCount + " 个错误，无法继续导入其他表",
                        result
                );
            }

            logger.info("患者基本信息导入成功，共导入 {} 条记录，现在可以继续导入其他表",
                    patientImport.getSuccessCount());

        } catch (DataValidationException e) {
            // 重新抛出数据验证异常
            throw e;
        } catch (Exception e) {
            logger.error("患者基本信息表导入时发生异常", e);

            ValidationResultDTO validationResult = new ValidationResultDTO();
            validationResult.setSuccess(false);
            validationResult.setValid(false);
            validationResult.setErrorCount(1);
            validationResult.setMessage("导入异常: " + e.getMessage());

            ValidationErrorDTO error = new ValidationErrorDTO();
            error.setRow(0);
            error.setPatientId(0);
            error.setField("系统错误");
            error.setValue("");
            error.setMessage("[患者基本信息] 导入时发生异常: " + e.getMessage());
            validationResult.setErrors(Arrays.asList(error));

            ImportResultDTO importResultDTO = new ImportResultDTO();
            importResultDTO.setSuccess(false);
            importResultDTO.setMessage("导入异常: " + e.getMessage());

            patientTableResult.put("tableName", "patient");
            patientTableResult.put("tableLabel", "患者基本信息");
            patientTableResult.put("validation", validationResult);
            patientTableResult.put("import", importResultDTO);
            patientTableResult.put("success", false);
            patientTableResult.put("valid", false);
            patientTableResult.put("errorCount", 1);

            tableResults.put("patient", patientTableResult);

            result.put("success", false);
            result.put("allValid", false);
            result.put("totalErrorCount", 1);
            result.put("allErrors", Arrays.asList(error));
            result.put("tables", tableResults);
            result.put("message", "患者基本信息导入失败：" + e.getMessage());

            throw new DataValidationException("患者基本信息导入失败：" + e.getMessage(), result);
        }

        // 第二步：先导入不需要调用外部API的表（7张表，不含injury_record）
        // injury_record需要调用高德API获取经纬度，放到最后执行以减少无效API调用
        logger.info("第二步：开始导入其他表的数据（不含受伤记录表）");
        List<TableInfo> tablesWithoutApi = Arrays.asList(
                new TableInfo("gcs_score", "GCS评分", excelFilePath1 -> gcsScoreImportService.validateAndImportGcsScoreData(excelFilePath1)),
                new TableInfo("rts_score", "RTS评分", excelFilePath1 -> rtsScoreImportService.validateAndImportRtsScoreData(excelFilePath1)),
                new TableInfo("patient_info_on_admission", "患者入室信息", excelFilePath1 -> patientInfoOnAdmissionImportService.validateAndImportPatientInfoOnAdmissionData(excelFilePath1)),
                new TableInfo("patient_info_off_admission", "患者离室信息", excelFilePath1 -> patientInfoOffAdmissionImportService.validateAndImportPatientInfoOffAdmissionData(excelFilePath1)),
                new TableInfo("intervention_time", "干预时间", excelFilePath1 -> interventionTimeImportService.validateAndImportInterventionTimeData(excelFilePath1)),
                new TableInfo("intervention_extra", "干预补充数据", excelFilePath1 -> interventionExtraImportService.validateAndImportInterventionExtraData(excelFilePath1)),
                new TableInfo("iss", "ISS数据", excelFilePath1 -> issPatientInjurySeverityImportService.validateAndImportIssData(excelFilePath1))
        );

        boolean allValid = true;
        int totalErrorCount = 0;
        List<ValidationErrorDTO> allErrors = new ArrayList<>();

        // 导入不需要调用API的7张表
        for (TableInfo table : tablesWithoutApi) {
            Map<String, Object> tableResult = importSingleTable(table, excelFilePath, allErrors);
            tableResults.put(table.name, tableResult);

            boolean tableValid = (Boolean) tableResult.get("valid");
            int errorCount = (Integer) tableResult.get("errorCount");
            boolean importSuccess = (Boolean) tableResult.get("importSuccess");
            if (!tableValid || errorCount > 0 || !importSuccess) {
                allValid = false;
                totalErrorCount += errorCount;
            }
        }

        // 第三步：检查前8张表是否有错误，如果有错误直接返回（避免调用高德API）
        if (!allValid || totalErrorCount > 0) {
            logger.warn("其他表导入失败，跳过受伤记录表导入（避免无效的API调用）。总错误数: {}", totalErrorCount);

            result.put("success", false);
            result.put("allValid", false);
            result.put("totalErrorCount", totalErrorCount);
            result.put("allErrors", allErrors);
            result.put("tables", tableResults);
            result.put("message", String.format("部分表导入失败，共发现 %d 个错误，事务已回滚", totalErrorCount));

            throw new DataValidationException(
                    "部分表导入失败，共发现 " + totalErrorCount + " 个错误，事务已回滚",
                    result
            );
        }

        // 第四步：前8张表都成功后，最后导入injury_record表（需要调用高德API获取经纬度）
        logger.info("第四步：前8张表导入成功，开始导入受伤记录表（将调用高德API获取经纬度）");
        TableInfo injuryRecordTable = new TableInfo("injury_record", "受伤记录",
                excelFilePath1 -> injuryRecordImportService.validateAndImportInjuryRecordData(excelFilePath1));

        Map<String, Object> injuryTableResult = importSingleTable(injuryRecordTable, excelFilePath, allErrors);
        tableResults.put("injury_record", injuryTableResult);

        boolean injuryValid = (Boolean) injuryTableResult.get("valid");
        int injuryErrorCount = (Integer) injuryTableResult.get("errorCount");
        boolean injuryImportSuccess = (Boolean) injuryTableResult.get("importSuccess");
        if (!injuryValid || injuryErrorCount > 0 || !injuryImportSuccess) {
            allValid = false;
            totalErrorCount += injuryErrorCount;
        }

        // 第五步：检查所有表的导入结果
        if (!allValid || totalErrorCount > 0) {
            logger.warn("受伤记录表导入失败，事务将回滚。总错误数: {}", totalErrorCount);

            result.put("success", false);
            result.put("allValid", false);
            result.put("totalErrorCount", totalErrorCount);
            result.put("allErrors", allErrors);
            result.put("tables", tableResults);
            result.put("message", String.format("受伤记录表导入失败，共发现 %d 个错误，事务已回滚", totalErrorCount));

            throw new DataValidationException(
                    "受伤记录表导入失败，共发现 " + totalErrorCount + " 个错误，事务已回滚",
                    result
            );
        }

        logger.info("所有表导入成功，数据已成功插入数据库");

        result.put("success", true);
        result.put("allValid", true);
        result.put("totalErrorCount", 0);
        result.put("allErrors", new ArrayList<>());
        result.put("tables", tableResults);
        result.put("message", "所有表的数据验证通过并成功导入");

        logger.info("批量导入完成: success=true, totalErrorCount=0");

        return result;
    }

    /**
     * 导入单张表并返回结果
     */
    private Map<String, Object> importSingleTable(TableInfo table, String excelFilePath, List<ValidationErrorDTO> allErrors) {
        logger.info("正在导入表: {} ({})", table.name, table.label);
        Map<String, Object> tableResult = new HashMap<>();

        try {
            Map<String, Object> importResult = table.service.validateAndImport(excelFilePath);

            ValidationResultDTO validationResult = (ValidationResultDTO) importResult.get("validation");
            ImportResultDTO importResultDTO = (ImportResultDTO) importResult.get("import");
            Boolean success = (Boolean) importResult.get("success");

            if (validationResult == null) {
                validationResult = new ValidationResultDTO();
                validationResult.setSuccess(false);
                validationResult.setValid(false);
                validationResult.setErrorCount(1);
                validationResult.setMessage("验证失败：无法获取验证结果");
                validationResult.setErrors(new ArrayList<>());
            }

            boolean tableValid = validationResult.getValid() != null && validationResult.getValid();
            int errorCount = validationResult.getErrorCount() != null ? validationResult.getErrorCount() : 0;
            boolean importSuccess = importResultDTO != null && importResultDTO.getSuccess();

            if (!tableValid || errorCount > 0 || !importSuccess) {
                if (validationResult.getErrors() != null) {
                    for (ValidationErrorDTO error : validationResult.getErrors()) {
                        if (allErrors.size() < MAX_ERRORS_IN_MEMORY) {
                            String originalMessage = error.getMessage() != null ? error.getMessage() : "";
                            error.setMessage("[" + table.label + "] " + originalMessage);
                            allErrors.add(error);
                        }
                    }
                }
                // 某些导入异常会在子服务被捕获并以 import.success=false 返回，
                // 此处补一条系统错误，避免出现“无校验错误但导入失败”被误判为成功。
                if (!importSuccess && allErrors.size() < MAX_ERRORS_IN_MEMORY) {
                    ValidationErrorDTO importFailureError = new ValidationErrorDTO();
                    importFailureError.setRow(0);
                    importFailureError.setPatientId(0);
                    importFailureError.setField("系统错误");
                    importFailureError.setValue("");
                    String importMessage = (importResultDTO != null && importResultDTO.getMessage() != null)
                            ? importResultDTO.getMessage()
                            : "导入失败";
                    importFailureError.setMessage("[" + table.label + "] " + importMessage);
                    allErrors.add(importFailureError);
                    errorCount += 1;
                }
                if (importResultDTO == null) {
                    importResultDTO = new ImportResultDTO();
                    importResultDTO.setSuccess(false);
                    importResultDTO.setMessage("验证失败，未导入数据");
                }
            }

            tableResult.put("tableName", table.name);
            tableResult.put("tableLabel", table.label);
            tableResult.put("validation", validationResult);
            tableResult.put("import", importResultDTO);
            tableResult.put("success", success);
            tableResult.put("valid", tableValid);
            tableResult.put("errorCount", errorCount);
            tableResult.put("importSuccess", importSuccess);

            logger.info("表 {} ({}) 导入完成: valid={}, importSuccess={}, errorCount={}", table.name, table.label, tableValid, importSuccess, errorCount);

        } catch (Exception e) {
            logger.error("导入表 {} ({}) 时发生异常", table.name, table.label, e);

            ValidationResultDTO validationResult = new ValidationResultDTO();
            validationResult.setSuccess(false);
            validationResult.setValid(false);
            validationResult.setErrorCount(1);
            validationResult.setMessage("导入异常: " + e.getMessage());

            ValidationErrorDTO error = new ValidationErrorDTO();
            error.setRow(0);
            error.setPatientId(0);
            error.setField("系统错误");
            error.setValue("");
            error.setMessage("[" + table.label + "] 导入时发生异常: " + e.getMessage());
            validationResult.setErrors(Arrays.asList(error));

            ImportResultDTO importResultDTO = new ImportResultDTO();
            importResultDTO.setSuccess(false);
            importResultDTO.setMessage("导入异常: " + e.getMessage());

            tableResult.put("tableName", table.name);
            tableResult.put("tableLabel", table.label);
            tableResult.put("validation", validationResult);
            tableResult.put("import", importResultDTO);
            tableResult.put("success", false);
            tableResult.put("valid", false);
            tableResult.put("errorCount", 1);
            tableResult.put("importSuccess", false);

            if (allErrors.size() < MAX_ERRORS_IN_MEMORY) {
                allErrors.add(error);
            }
        }

        return tableResult;
    }
}

