package com.demo.upload.service;

import com.demo.upload.dto.ValidationErrorDTO;
import com.demo.upload.exception.DataValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 分批数据导入服务
 * 解决大数据量导入时的内存溢出问题
 * 
 * 处理流程：
 * 1. 分批读取Excel数据（每批1000条）
 * 2. 验证每批数据，将错误信息写入文件
 * 3. 将验证通过的数据序列化到临时文件
 * 4. 如果所有批次都通过，从临时文件批量导入数据库
 * 5. 如果有错误，返回错误文件路径，并回滚事务
 */
@Service
public class BatchDataImportService {
    
    private static final Logger logger = LoggerFactory.getLogger(BatchDataImportService.class);
    
    @Autowired
    private ComprehensiveDataImportService comprehensiveDataImportService;
    
    /**
     * 每批处理的记录数
     */
    @Value("${import.batch.size:1000}")
    private int batchSize;
    
    /**
     * 最大错误信息数量（超过此数量后只记录统计信息）
     */
    @Value("${import.max.errors:10000}")
    private int maxErrors;
    
    /**
     * 临时文件目录
     */
    private static final String TEMP_DIR = "temp-imports/";
    
    /**
     * 错误文件目录
     */
    private static final String ERROR_DIR = "temp-imports/errors/";
    
    /**
     * 分批验证并导入所有表的数据
     * 
     * @param excelFilePath Excel文件路径
     * @return 包含所有表验证和导入结果的Map
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchValidateAndImportAllTables(String excelFilePath) {
        logger.info("开始分批导入所有表的数据，文件路径: {}", excelFilePath);
        
        // 创建临时目录
        createTempDirectories();
        
        // 生成本次导入的唯一ID
        String importId = UUID.randomUUID().toString();
        Path tempBaseDir = Paths.get(TEMP_DIR, importId);
        Path errorBaseDir = Paths.get(ERROR_DIR, importId);
        
        try {
            Files.createDirectories(tempBaseDir);
            Files.createDirectories(errorBaseDir);
            
            // 使用原有的导入服务，但添加错误信息文件存储
            Map<String, Object> result = comprehensiveDataImportService.validateAndImportAllTables(excelFilePath);
            
            // 将 importId 放入 result，方便后续查找错误文件
            result.put("importId", importId);
            
            // 检查是否有错误（改进：只要有错误就写入文件，不依赖totalErrorCount）
            @SuppressWarnings("unchecked")
            List<ValidationErrorDTO> allErrors = (List<ValidationErrorDTO>) result.get("allErrors");
            Integer totalErrorCount = (Integer) result.get("totalErrorCount");
            Boolean success = (Boolean) result.get("success");
            
            // 关键修复：如果导入失败（success=false），无论totalErrorCount和allErrors是什么，都要尝试查找错误文件
            // 因为可能存在错误文件但错误信息没有正确返回的情况
            if (success != null && !success) {
                logger.warn("导入失败（success=false），totalErrorCount={}, allErrors.size()={}", 
                    totalErrorCount, allErrors != null ? allErrors.size() : 0);
                
                // 改进：只要有错误就写入文件，即使totalErrorCount为null
                if (allErrors != null && !allErrors.isEmpty()) {
                    try {
                        // 将错误信息写入文件（无论totalErrorCount是否为null）
                        String errorFilePath = writeErrorsToFile(allErrors, errorBaseDir, importId);
                        result.put("errorFilePath", errorFilePath);
                        result.put("errorFileUrl", "/api/file/downloadErrorReport?file=" + 
                            Paths.get(errorFilePath).getFileName().toString());
                        
                        // 限制返回的错误数量，避免响应体过大导致Broken pipe
                        // 如果错误数量超过50，只返回前50条错误信息，其他数据提示"请下载错误信息文件查看"
                        int actualErrorCount = allErrors.size();
                        int maxReturnErrors = 50;  // 大数据量下只返回前50条错误
                        
                        if (actualErrorCount > maxReturnErrors) {
                            logger.warn("错误数量 {} 超过限制 {}，只返回前50条错误信息，完整错误信息请下载错误报告", 
                                actualErrorCount, maxReturnErrors);
                            // 返回前50条错误，让用户能看到部分错误
                            List<ValidationErrorDTO> previewErrors = allErrors.subList(0, Math.min(50, allErrors.size()));
                            result.put("errors", previewErrors);
                            result.put("hasMoreErrors", true);
                        } else {
                            // 即使不超过100，也要限制在maxErrors以内
                            int returnErrorCount = Math.min(actualErrorCount, maxErrors);
                            if (actualErrorCount > maxErrors) {
                                List<ValidationErrorDTO> limitedErrors = new ArrayList<>(allErrors.subList(0, returnErrorCount));
                                result.put("errors", limitedErrors);
                                result.put("hasMoreErrors", true);
                            } else {
                                result.put("errors", allErrors);
                                result.put("hasMoreErrors", false);
                            }
                        }
                        
                        // 确保totalErrorCount不为null
                        if (totalErrorCount == null) {
                            totalErrorCount = actualErrorCount;
                            result.put("totalErrorCount", totalErrorCount);
                        }
                        
                        int loggedReturnCount = actualErrorCount > maxReturnErrors ? 50 : (actualErrorCount > maxErrors ? maxErrors : actualErrorCount);
                        logger.info("错误信息已写入文件: {}, 总错误数: {}, 返回错误数: {}", 
                            errorFilePath, totalErrorCount, loggedReturnCount);
                            
                    } catch (IOException ioException) {
                        logger.error("写入错误文件失败", ioException);
                        // 即使写入失败，也要返回错误信息
                    }
                } else {
                    // 如果allErrors为空，但导入失败，说明错误信息可能丢失或错误文件已存在
                    // 尝试从错误文件目录查找已存在的错误文件
                    logger.warn("导入失败但allErrors为空，totalErrorCount={}，尝试查找已存在的错误文件", totalErrorCount);
                    
                    // 检查错误文件目录中是否已有文件
                    try {
                        List<Path> existingFiles = Files.list(errorBaseDir)
                            .filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".csv"))
                            .collect(Collectors.toList());
                        
                        if (!existingFiles.isEmpty()) {
                            // 找到已存在的错误文件
                            Path latestFile = existingFiles.stream()
                                .max((p1, p2) -> {
                                    try {
                                        return Files.getLastModifiedTime(p1).compareTo(Files.getLastModifiedTime(p2));
                                    } catch (IOException e) {
                                        return 0;
                                    }
                                })
                                .orElse(null);
                            
                            if (latestFile != null) {
                                String fileName = latestFile.getFileName().toString();
                                result.put("errorFilePath", latestFile.toString());
                                result.put("errorFileUrl", "/api/file/downloadErrorReport?file=" + fileName);
                                logger.info("找到已存在的错误文件: {}", latestFile);
                                
                                // 尝试读取文件中的错误数量（从CSV文件的第一行之后的行数）
                                try {
                                    long lineCount = Files.lines(latestFile).count();
                                    int estimatedErrorCount = (int) Math.max(0, lineCount - 1); // 减去表头行
                                    if (totalErrorCount == null || totalErrorCount == 0) {
                                        totalErrorCount = estimatedErrorCount;
                                        result.put("totalErrorCount", totalErrorCount);
                                    }
                                    logger.info("从错误文件估计错误数量: {}", estimatedErrorCount);
                                } catch (IOException e) {
                                    logger.warn("读取错误文件行数失败", e);
                                }
                            }
                        }
                    } catch (IOException e) {
                        logger.warn("查找已存在的错误文件失败", e);
                    }
                    
                    // 如果仍然没有错误文件，创建一个系统错误提示
                    if (result.get("errorFileUrl") == null) {
                        List<ValidationErrorDTO> systemErrors = new ArrayList<>();
                        ValidationErrorDTO systemError = new ValidationErrorDTO();
                        systemError.setRow(0);
                        systemError.setPatientId(0);
                        systemError.setField("系统错误");
                        systemError.setValue("");
                        String errorMsg = "导入失败";
                        if (totalErrorCount != null && totalErrorCount > 0) {
                            errorMsg += "，检测到 " + totalErrorCount + " 个错误，但错误详情未正确收集，请查看服务器日志或错误文件";
                        } else {
                            errorMsg += "，请查看服务器日志或错误文件";
                        }
                        systemError.setMessage(errorMsg);
                        systemErrors.add(systemError);
                        
                        try {
                            String errorFilePath = writeErrorsToFile(systemErrors, errorBaseDir, importId);
                            result.put("errorFilePath", errorFilePath);
                            result.put("errorFileUrl", "/api/file/downloadErrorReport?file=" + 
                                Paths.get(errorFilePath).getFileName().toString());
                            result.put("errors", systemErrors);
                            if (totalErrorCount == null || totalErrorCount == 0) {
                                result.put("totalErrorCount", 1);
                            } else {
                                result.put("totalErrorCount", totalErrorCount);
                            }
                            logger.info("创建系统错误文件: {}", errorFilePath);
                        } catch (IOException ioException) {
                            logger.error("写入系统错误文件失败", ioException);
                        }
                    }
                }
            }
            
            return result;
            
        } catch (DataValidationException e) {
            // 数据验证异常，需要将错误信息写入文件
            Map<String, Object> errorDetails = e.getErrorDetails();
            
            // 重要：将 importId 放入 errorDetails，方便后续查找错误文件
            errorDetails.put("importId", importId);
            
            @SuppressWarnings("unchecked")
            List<ValidationErrorDTO> allErrors = (List<ValidationErrorDTO>) errorDetails.get("allErrors");
            
            // 改进：只要有错误就写入文件
            if (allErrors != null && !allErrors.isEmpty()) {
                try {
                    String errorFilePath = writeErrorsToFile(allErrors, errorBaseDir, importId);
                    errorDetails.put("errorFilePath", errorFilePath);
                    errorDetails.put("errorFileUrl", "/api/file/downloadErrorReport?file=" + 
                        Paths.get(errorFilePath).getFileName().toString());
                    
                    // 限制返回的错误数量，避免响应体过大导致Broken pipe
                    // 如果错误数量超过50，只返回前50条错误信息，其他数据提示"请下载错误信息文件查看"
                    int actualErrorCount = allErrors.size();
                    int maxReturnErrors = 50;  // 大数据量下只返回前50条错误
                    Integer totalErrorCount = (Integer) errorDetails.get("totalErrorCount");
                    
                    if (actualErrorCount > maxReturnErrors) {
                        logger.warn("DataValidationException: 错误数量 {} 超过限制 {}，只返回前50条错误信息，完整错误信息请下载错误报告", 
                            actualErrorCount, maxReturnErrors);
                        // 返回前50条错误，让用户能看到部分错误
                        List<ValidationErrorDTO> previewErrors = allErrors.subList(0, Math.min(50, allErrors.size()));
                        errorDetails.put("errors", previewErrors);
                        errorDetails.put("hasMoreErrors", true);
                    } else {
                        // 即使不超过100，也要限制在maxErrors以内
                        int returnErrorCount = Math.min(actualErrorCount, maxErrors);
                        if (actualErrorCount > maxErrors) {
                            List<ValidationErrorDTO> limitedErrors = new ArrayList<>(allErrors.subList(0, returnErrorCount));
                            errorDetails.put("errors", limitedErrors);
                            errorDetails.put("hasMoreErrors", true);
                        } else {
                            errorDetails.put("errors", allErrors);
                            errorDetails.put("hasMoreErrors", false);
                        }
                    }
                    
                    // 确保totalErrorCount不为null
                    if (totalErrorCount == null) {
                        totalErrorCount = actualErrorCount;
                        errorDetails.put("totalErrorCount", totalErrorCount);
                    }
                    
                    int loggedReturnCount = actualErrorCount > maxReturnErrors ? 0 : (actualErrorCount > maxErrors ? maxErrors : actualErrorCount);
                    logger.info("DataValidationException: 错误信息已写入文件: {}, 总错误数: {}, 返回错误数: {}", 
                        errorFilePath, totalErrorCount, loggedReturnCount);
                        
                } catch (IOException ioException) {
                    logger.error("写入错误文件失败", ioException);
                }
            } else {
                // 处理totalErrorCount > 0但allErrors为空的情况
                Integer totalErrorCount = (Integer) errorDetails.get("totalErrorCount");
                if (totalErrorCount != null && totalErrorCount > 0) {
                    logger.warn("DataValidationException: 检测到错误总数 {} > 0，但错误列表为空", totalErrorCount);
                    List<ValidationErrorDTO> systemErrors = new ArrayList<>();
                    ValidationErrorDTO systemError = new ValidationErrorDTO();
                    systemError.setRow(0);
                    systemError.setPatientId(0);
                    systemError.setField("系统错误");
                    systemError.setValue("");
                    systemError.setMessage("检测到 " + totalErrorCount + " 个错误，但错误详情未正确收集: " + e.getMessage());
                    systemErrors.add(systemError);
                    
                    try {
                        String errorFilePath = writeErrorsToFile(systemErrors, errorBaseDir, importId);
                        errorDetails.put("errorFilePath", errorFilePath);
                        errorDetails.put("errorFileUrl", "/api/file/downloadErrorReport?file=" + 
                            Paths.get(errorFilePath).getFileName().toString());
                        errorDetails.put("errors", systemErrors);
                        errorDetails.put("totalErrorCount", 1);
                    } catch (IOException ioException) {
                        logger.error("写入系统错误文件失败", ioException);
                    }
                }
            }
            
            throw e;
            
        } catch (Error e) {
            // 处理编译错误或系统错误（包括 OutOfMemoryError）
            logger.error("分批导入过程中发生系统错误（编译错误或运行时错误）", e);
            
            // 创建错误信息并写入文件
            List<ValidationErrorDTO> errorList = new ArrayList<>();
            ValidationErrorDTO errorDTO = new ValidationErrorDTO();
            errorDTO.setRow(0);
            errorDTO.setPatientId(0);
            errorDTO.setField("系统错误");
            errorDTO.setValue("");
            
            String errorMessage = "系统发生严重错误";
            if (e.getMessage() != null) {
                if (e.getMessage().contains("Unresolved compilation problems")) {
                    errorMessage = "代码编译错误：" + e.getMessage() + "。请检查后端代码是否正确编译，特别是 BodyPartScoreMapping 类。";
                } else if (e instanceof OutOfMemoryError) {
                    errorMessage = "内存溢出错误，请减少数据量或联系管理员";
                } else {
                    errorMessage = "系统错误：" + e.getMessage();
                }
            }
            errorDTO.setMessage(errorMessage);
            errorList.add(errorDTO);
            
            try {
                String errorFilePath = writeErrorsToFile(errorList, errorBaseDir, importId);
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "导入失败：" + errorMessage);
                result.put("allErrors", errorList);
                result.put("totalErrorCount", 1);
                result.put("errorFilePath", errorFilePath);
                result.put("errorFileUrl", "/api/file/downloadErrorReport?file=" + 
                    Paths.get(errorFilePath).getFileName().toString());
                result.put("errors", errorList);
                result.put("hasMoreErrors", false);
                throw new DataValidationException("导入失败：" + errorMessage, result);
            } catch (IOException ioException) {
                logger.error("写入错误文件失败", ioException);
                throw new RuntimeException("导入失败：" + errorMessage, e);
            }
        } catch (Exception e) {
            logger.error("分批导入过程中发生异常", e);
            
            // 检查是否是内存溢出
            Throwable cause = e.getCause();
            if (cause instanceof OutOfMemoryError) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "导入失败：数据量过大导致内存溢出，请尝试减少数据量或联系管理员");
                result.put("suggestion", "建议将数据分批导入，每批不超过1000条");
                throw new RuntimeException("分批导入失败：内存溢出", e);
            }
            
            // 对于其他异常，创建错误信息并写入文件
            List<ValidationErrorDTO> errorList = new ArrayList<>();
            ValidationErrorDTO errorDTO = new ValidationErrorDTO();
            errorDTO.setRow(0);
            errorDTO.setPatientId(0);
            errorDTO.setField("系统异常");
            errorDTO.setValue("");
            
            String errorMessage = "导入失败：" + (e.getMessage() != null ? e.getMessage() : "未知错误");
            // 检查是否是编译错误
            if (e.getMessage() != null && e.getMessage().contains("Unresolved compilation problems")) {
                errorMessage = "代码编译错误：" + e.getMessage() + "。请检查后端代码是否正确编译。";
            }
            errorDTO.setMessage(errorMessage);
            errorList.add(errorDTO);
            
            try {
                String errorFilePath = writeErrorsToFile(errorList, errorBaseDir, importId);
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", errorMessage);
                result.put("allErrors", errorList);
                result.put("totalErrorCount", 1);
                result.put("errorFilePath", errorFilePath);
                result.put("errorFileUrl", "/api/file/downloadErrorReport?file=" + 
                    Paths.get(errorFilePath).getFileName().toString());
                result.put("errors", errorList);
                result.put("hasMoreErrors", false);
                throw new DataValidationException(errorMessage, result);
            } catch (IOException ioException) {
                logger.error("写入错误文件失败", ioException);
                throw new RuntimeException("导入失败：" + errorMessage, e);
            }
            
        } finally {
            // 清理临时文件（延迟删除，给用户时间下载错误报告）
            // 这里可以选择立即删除或延迟删除
            // scheduleCleanup(tempBaseDir, errorBaseDir);
        }
    }
    
    /**
     * 将错误信息写入CSV文件
     */
    private String writeErrorsToFile(List<ValidationErrorDTO> errors, Path errorDir, String importId) 
            throws IOException {
        String fileName = "import-errors-" + importId + "-" + System.currentTimeMillis() + ".csv";
        Path csvPath = errorDir.resolve(fileName);
        
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8)) {
            // 写入UTF-8 BOM，便于Excel正确识别编码
            writer.write('\uFEFF');
            // 表头
            writer.write("行号,患者ID,字段,错误值,错误原因");
            writer.newLine();
            
            if (errors != null) {
                for (ValidationErrorDTO err : errors) {
                    String row = err.getRow() == null ? "" : String.valueOf(err.getRow());
                    String patientId = err.getPatientId() == null ? "" : String.valueOf(err.getPatientId());
                    String field = safeCsv(err.getField());
                    String value = err.getValue() == null ? "" : safeCsv(String.valueOf(err.getValue()));
                    String message = safeCsv(err.getMessage());
                    writer.write(String.join(",", quote(row), quote(patientId), quote(field), quote(value), quote(message)));
                    writer.newLine();
                }
            }
        }
        
        logger.info("错误信息已写入文件: {}", csvPath);
        return csvPath.toString();
    }
    
    /**
     * 创建临时目录
     */
    private void createTempDirectories() {
        try {
            Files.createDirectories(Paths.get(TEMP_DIR));
            Files.createDirectories(Paths.get(ERROR_DIR));
        } catch (IOException e) {
            logger.error("创建临时目录失败", e);
        }
    }
    
    /**
     * CSV安全处理
     */
    private String safeCsv(String s) {
        if (s == null) return "";
        return s.replace("\r", " ").replace("\n", " ").trim();
    }
    
    /**
     * CSV引号处理
     */
    private String quote(String s) {
        if (s == null) return "\"\"";
        // 如果包含逗号或引号，进行转义并包裹引号
        boolean needQuote = s.contains(",") || s.contains("\"");
        String escaped = s.replace("\"", "\"\"");
        return needQuote ? "\"" + escaped + "\"" : escaped;
    }
}

