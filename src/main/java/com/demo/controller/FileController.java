package com.demo.controller;

import com.demo.Service.impl.IInjuryRecordService;
import com.demo.dto.Result;
import com.demo.entity.InjuryRecord;
import com.demo.upload.exception.DataValidationException;
import com.demo.upload.service.ComprehensiveDataImportService;
import com.demo.utils.ExcelImportUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.demo.upload.dto.ValidationErrorDTO;

@RestController
@RequestMapping("/api/file")
public class FileController {
    
    private static final Logger logger = LoggerFactory.getLogger(FileController.class);
    
    @Autowired
    private IInjuryRecordService injuryRecordService;
    
    @Autowired
    private ComprehensiveDataImportService comprehensiveDataImportService;
    
    @Autowired
    private com.demo.upload.service.BatchDataImportService batchDataImportService;
    
    // 文件上传目录
    private static final String UPLOAD_DIR = "uploads/";
    
    @PostMapping("/uploadInjuryRecordExcel")
    public Result uploadInjuryRecordExcel(@RequestParam("file") MultipartFile file) {
        try {
            ExcelImportUtil.importExcelToDb(file, injuryRecordService, InjuryRecord.class);
            return Result.ok("导入成功");
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("导入失败：" + e.getMessage());
        }
    }
    
    @PostMapping("/uploadPatientExcel")
    public ResponseEntity<Map<String, Object>> uploadPatientExcel(@RequestParam("file") MultipartFile file) {
        try {
            logger.info("=== 开始处理文件上传 ===");
            logger.info("文件名: {}", file.getOriginalFilename());
            logger.info("文件大小: {}", file.getSize());
            
            // 保存文件到服务器
            String savedFilePath = saveUploadedFile(file);
            logger.info("文件保存路径: {}", savedFilePath);
            
            // 调用分批导入服务进行数据导入（整合所有9张表，支持大数据量）
            logger.info("=== 开始分批导入所有表的数据 ===");
            Map<String, Object> importResult = batchDataImportService.batchValidateAndImportAllTables(savedFilePath);
            
            Boolean success = (Boolean) importResult.get("success");
            String message = (String) importResult.get("message");
            Integer totalErrorCount = (Integer) importResult.get("totalErrorCount");
            
            // 关键修复：记录导入结果的关键信息，便于调试
            logger.info("导入结果: success={}, totalErrorCount={}, message={}", success, totalErrorCount, message);
            logger.info("导入结果中的errorFileUrl: {}", importResult.get("errorFileUrl"));
            logger.info("导入结果中的errors: {}", importResult.get("errors"));
            
            if (success != null && success) {
                logger.info("=== 导入成功，返回成功响应 ===");
                Map<String, Object> result = new HashMap<>();
                result.put("message", message);
                result.put("savedFilePath", savedFilePath);
                result.put("fileName", file.getOriginalFilename());
                result.put("fileSize", file.getSize());
                result.put("tables", importResult.get("tables"));
                
                // 返回前端期望的格式：{code: 200, data: {...}}
                Map<String, Object> response = new HashMap<>();
                response.put("code", 200);
                response.put("data", result);
                response.put("message", message);
                
                return new ResponseEntity<Map<String, Object>>(response, HttpStatus.OK);
            } else {
                logger.warn("=== 导入失败，返回失败响应 ===");
                logger.warn("总错误数: {}", totalErrorCount);
                
                // 如果导入失败，删除保存的文件
                deleteFile(savedFilePath);
                
                // 返回前端期望的格式：{code: 500, message: "...", errors: [...]}
                Map<String, Object> response = new HashMap<>();
                response.put("code", 500);
                response.put("message", message);
                response.put("totalErrorCount", totalErrorCount);
                
                // 优先使用文件中的错误信息URL（如果已生成）
                String errorFileUrl = (String) importResult.get("errorFileUrl");
                String errorFilePath = (String) importResult.get("errorFilePath");
                
                // 改进：只要导入失败（success=false），无论 totalErrorCount 是多少，都要尝试查找错误文件
                // 因为可能存在 totalErrorCount 为 0 但实际有错误文件的情况
                if (errorFileUrl == null) {
                    logger.warn("导入失败但 errorFileUrl 为空，尝试查找错误文件（totalErrorCount={}）", totalErrorCount);
                    // 尝试从 importResult 中获取 importId
                    String importId = (String) importResult.get("importId");
                    errorFileUrl = findLatestErrorFile(importId);
                    if (errorFileUrl != null) {
                        logger.info("通过查找找到错误文件URL: {}", errorFileUrl);
                    } else {
                        logger.warn("未找到错误文件，importId={}", importId);
                    }
                }
                
                if (errorFileUrl != null) {
                    response.put("errorExportUrl", errorFileUrl);
                    response.put("errorFilePath", errorFilePath);
                    logger.info("返回错误文件URL: {}", errorFileUrl);
                }
                
                // 检查是否有更多错误（超过限制）
                Boolean hasMoreErrors = (Boolean) importResult.get("hasMoreErrors");
                Object errorsObj = importResult.get("errors");
                
                // 改进：检查错误列表大小，如果过大则只返回空列表，引导用户下载错误文件
                if (errorsObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<ValidationErrorDTO> errors = (List<ValidationErrorDTO>) errorsObj;
                    
                    // 改进：如果错误数量超过50条，只返回前50条错误信息，其他数据提示"请下载错误信息文件查看"
                    if (errors.size() > 50) {
                        logger.warn("错误数量 {} 超过限制 50，只返回前50条错误信息，完整错误信息请下载错误报告", errors.size());
                        // 返回前50条错误，让用户能看到部分错误
                        List<ValidationErrorDTO> previewErrors = errors.subList(0, Math.min(50, errors.size()));
                        response.put("errors", previewErrors);
                        response.put("hasMoreErrors", true);
                        if (errorFileUrl != null) {
                            response.put("message", message + "（错误数量过多，共 " + totalErrorCount + " 个错误，已显示前50条，请下载错误报告查看完整信息）");
                        } else {
                            response.put("message", message + "（错误数量过多，共 " + totalErrorCount + " 个错误，请下载错误报告查看详细信息）");
                        }
                    } else {
                        response.put("errors", errors);
                        if (hasMoreErrors != null && hasMoreErrors) {
                            response.put("hasMoreErrors", true);
                            response.put("message", message + "（错误数量过多，请下载错误报告查看详细信息）");
                        }
                    }
                } else {
                response.put("errors", errorsObj);
                    if (hasMoreErrors != null && hasMoreErrors) {
                        response.put("hasMoreErrors", true);
                        response.put("message", message + "（错误数量过多，请下载错误报告查看详细信息）");
                    }
                }

                // 如果没有生成错误文件，尝试生成（兼容旧逻辑）
                // 注意：即使 errorFileUrl 为空，也要继续尝试查找，因为可能存在错误文件但未正确返回的情况
                if (errorFileUrl == null) {
                try {
                    if (errorsObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<ValidationErrorDTO> errors = (List<ValidationErrorDTO>) errorsObj;
                            if (!errors.isEmpty()) {
                        String fileName = writeErrorsCsv(errors);
                                errorFileUrl = "/api/file/downloadErrorReport?file=" + fileName;
                        response.put("errorExportFile", fileName);
                                response.put("errorExportUrl", errorFileUrl);
                                logger.info("通过 writeErrorsCsv 生成错误文件: {}", fileName);
                            } else {
                                // 即使 errors 为空，只要导入失败，也尝试查找文件（作为兜底方案）
                                logger.warn("errors为空，但导入失败，尝试查找错误文件作为兜底方案");
                                String importId = (String) importResult.get("importId");
                                errorFileUrl = findLatestErrorFile(importId);
                                if (errorFileUrl != null) {
                                    response.put("errorExportUrl", errorFileUrl);
                                    logger.info("errors为空但找到错误文件（兜底）: {}", errorFileUrl);
                                }
                            }
                        } else {
                            // errorsObj 不是 List，也尝试查找文件
                            logger.warn("errorsObj不是List类型，尝试查找错误文件作为兜底方案");
                            String importId = (String) importResult.get("importId");
                            errorFileUrl = findLatestErrorFile(importId);
                            if (errorFileUrl != null) {
                                response.put("errorExportUrl", errorFileUrl);
                                logger.info("找到错误文件（兜底）: {}", errorFileUrl);
                            }
                    }
                } catch (Exception ex) {
                    logger.warn("生成错误导出文件失败: {}", ex.getMessage());
                        // 即使生成失败，也尝试查找文件（作为最后的兜底方案）
                        logger.warn("生成失败，尝试查找错误文件作为最后的兜底方案");
                        String importId = (String) importResult.get("importId");
                        errorFileUrl = findLatestErrorFile(importId);
                        if (errorFileUrl != null) {
                            response.put("errorExportUrl", errorFileUrl);
                            logger.info("生成失败但找到错误文件（最后兜底）: {}", errorFileUrl);
                        }
                    }
                }
                
                // 最终检查：如果仍然没有 errorFileUrl，但导入失败，强制查找一次（确保兜底）
                if (errorFileUrl == null) {
                    logger.warn("所有方法都未找到错误文件，进行最后一次强制查找");
                    String importId = (String) importResult.get("importId");
                    errorFileUrl = findLatestErrorFile(importId);
                    if (errorFileUrl != null) {
                        response.put("errorExportUrl", errorFileUrl);
                        logger.info("最后一次查找成功: {}", errorFileUrl);
                    } else {
                        logger.error("最终未能找到错误文件，importId={}, totalErrorCount={}", importId, totalErrorCount);
                    }
                }
                response.put("tables", importResult.get("tables"));
                
                return new ResponseEntity<Map<String, Object>>(response, HttpStatus.OK);
            }
        } catch (DataValidationException e) {
            logger.warn("=== 数据验证失败 ===");
            logger.warn("错误信息: {}", e.getMessage());
            
            // 从异常中提取错误详情
            Map<String, Object> errorDetails = e.getErrorDetails();
            
            // 返回前端期望的格式：{code: 500, message: "...", errors: [...], tables: {...}}
            Map<String, Object> response = new HashMap<>();
            response.put("code", 500);
            response.put("message", e.getMessage());
            response.put("totalErrorCount", errorDetails.get("totalErrorCount"));
            
            // 优先使用文件中的错误信息URL（如果已生成）
            String errorFileUrl = (String) errorDetails.get("errorFileUrl");
            String errorFilePath = (String) errorDetails.get("errorFilePath");
            
            // 改进：只要抛出 DataValidationException，无论 totalErrorCount 是多少，都要尝试查找错误文件
            if (errorFileUrl == null) {
                logger.warn("DataValidationException: errorFileUrl 为空，尝试查找错误文件");
                // 尝试从 errorDetails 中获取 importId
                String importId = (String) errorDetails.get("importId");
                errorFileUrl = findLatestErrorFile(importId);
                if (errorFileUrl != null) {
                    logger.info("通过查找找到错误文件URL: {}", errorFileUrl);
                } else {
                    logger.warn("DataValidationException: 未找到错误文件，importId={}", importId);
                }
            }
            
            // 检查是否有更多错误（超过限制）
            Boolean hasMoreErrors = (Boolean) errorDetails.get("hasMoreErrors");
            Object errorsObj = errorDetails.get("errors");
            Integer totalErrorCount = (Integer) errorDetails.get("totalErrorCount");
            
            // 改进：如果错误数量超过50条，只返回前50条错误信息，其他数据提示"请下载错误信息文件查看"
            if (errorsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<ValidationErrorDTO> errors = (List<ValidationErrorDTO>) errorsObj;
                
                // 如果错误数量超过50条，只返回前50条错误信息，其余通过错误文件查看
                if (errors.size() > 50) {
                    logger.warn("DataValidationException: 错误数量 {} 超过限制 50，只返回前50条错误信息，完整错误信息请下载错误报告", errors.size());
                    // 返回前50条错误，让用户能看到部分错误
                    List<ValidationErrorDTO> previewErrors = errors.subList(0, Math.min(50, errors.size()));
                    response.put("errors", previewErrors);
                    response.put("hasMoreErrors", true);
                    if (errorFileUrl != null) {
                        response.put("message", e.getMessage() + "（错误数量过多，共 " + totalErrorCount + " 个错误，已显示前50条，请下载错误报告查看完整信息）");
                    } else {
                        response.put("message", e.getMessage() + "（错误数量过多，共 " + totalErrorCount + " 个错误，请下载错误报告查看详细信息）");
                    }
                } else {
                    response.put("errors", errors);
                    if (hasMoreErrors != null && hasMoreErrors) {
                        response.put("hasMoreErrors", true);
                        response.put("message", e.getMessage() + "（错误数量过多，请下载错误报告查看详细信息）");
                    }
                }
            } else {
            response.put("errors", errorsObj);
                if (hasMoreErrors != null && hasMoreErrors) {
                    response.put("hasMoreErrors", true);
                    response.put("message", e.getMessage() + "（错误数量过多，请下载错误报告查看详细信息）");
                }
            }
            
            // 确保错误文件URL总是被返回（即使之前为空）
            if (errorFileUrl != null) {
                response.put("errorExportUrl", errorFileUrl);
                response.put("errorFilePath", errorFilePath);
                logger.info("返回错误文件URL: {}", errorFileUrl);
            } else {
                // 如果 errorFileUrl 仍然为空，强制查找一次
                logger.warn("DataValidationException: errorFileUrl 仍然为空，强制查找错误文件");
                String importId = (String) errorDetails.get("importId");
                errorFileUrl = findLatestErrorFile(importId);
                if (errorFileUrl != null) {
                    response.put("errorExportUrl", errorFileUrl);
                    logger.info("强制查找成功，返回错误文件URL: {}", errorFileUrl);
                } else {
                    logger.error("DataValidationException: 强制查找也失败，importId={}, totalErrorCount={}", importId, totalErrorCount);
                }
            }
            
            // 如果没有生成错误文件，尝试生成（兼容旧逻辑）
            // 注意：即使 errorFileUrl 为空，也要继续尝试查找，因为可能存在错误文件但未正确返回的情况
            if (errorFileUrl == null) {
            try {
                if (errorsObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<ValidationErrorDTO> errors = (List<ValidationErrorDTO>) errorsObj;
                        if (!errors.isEmpty()) {
                    String fileName = writeErrorsCsv(errors);
                            errorFileUrl = "/api/file/downloadErrorReport?file=" + fileName;
                    response.put("errorExportFile", fileName);
                            response.put("errorExportUrl", errorFileUrl);
                            logger.info("通过 writeErrorsCsv 生成错误文件: {}", fileName);
                        } else {
                            // 即使 errors 为空，只要抛出异常，也尝试查找文件（作为兜底方案）
                            logger.warn("DataValidationException: errors为空，尝试查找错误文件作为兜底方案");
                            String importId = (String) errorDetails.get("importId");
                            errorFileUrl = findLatestErrorFile(importId);
                            if (errorFileUrl != null) {
                                response.put("errorExportUrl", errorFileUrl);
                                logger.info("errors为空但找到错误文件（兜底）: {}", errorFileUrl);
                            }
                        }
                    } else {
                        // errorsObj 不是 List，也尝试查找文件
                        logger.warn("DataValidationException: errorsObj不是List类型，尝试查找错误文件作为兜底方案");
                        String importId = (String) errorDetails.get("importId");
                        errorFileUrl = findLatestErrorFile(importId);
                        if (errorFileUrl != null) {
                            response.put("errorExportUrl", errorFileUrl);
                            logger.info("找到错误文件（兜底）: {}", errorFileUrl);
                        }
                }
            } catch (Exception ex) {
                logger.warn("生成错误导出文件失败: {}", ex.getMessage());
                    // 即使生成失败，也尝试查找文件（作为最后的兜底方案）
                    logger.warn("DataValidationException: 生成失败，尝试查找错误文件作为最后的兜底方案");
                    String importId = (String) errorDetails.get("importId");
                    errorFileUrl = findLatestErrorFile(importId);
                    if (errorFileUrl != null) {
                        response.put("errorExportUrl", errorFileUrl);
                        logger.info("生成失败但找到错误文件（最后兜底）: {}", errorFileUrl);
                    }
                }
            }
            
            // 最终检查：如果仍然没有 errorFileUrl，但抛出异常，强制查找一次（确保兜底）
            if (errorFileUrl == null) {
                logger.warn("DataValidationException: 所有方法都未找到错误文件，进行最后一次强制查找");
                String importId = (String) errorDetails.get("importId");
                errorFileUrl = findLatestErrorFile(importId);
                if (errorFileUrl != null) {
                    response.put("errorExportUrl", errorFileUrl);
                    logger.info("最后一次查找成功: {}", errorFileUrl);
                } else {
                    logger.error("DataValidationException: 最终未能找到错误文件，importId={}", importId);
                }
            }
            response.put("tables", errorDetails.get("tables"));
            
            // 最终日志：记录响应内容，确保错误文件URL被正确返回
            logger.info("DataValidationException响应: code=500, totalErrorCount={}, errorFileUrl={}, errorsCount={}, hasMoreErrors={}", 
                totalErrorCount, 
                response.get("errorExportUrl"),
                errorsObj instanceof List ? ((List<?>) errorsObj).size() : 0,
                response.get("hasMoreErrors"));
            
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (Error e) {
            // 处理编译错误或系统错误（包括 OutOfMemoryError）
            logger.error("=== 导入过程中发生系统错误（编译错误或运行时错误）===", e);
            
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
            
            // 返回前端期望的格式
            Map<String, Object> response = new HashMap<>();
            response.put("code", 500);
            response.put("message", "导入失败：" + errorMessage);
            response.put("totalErrorCount", 1);
            response.put("errors", errorList);
            
            // 尝试生成错误文件
            String errorFileUrl = null;
            try {
                String fileName = writeErrorsCsv(errorList);
                errorFileUrl = "/api/file/downloadErrorReport?file=" + fileName;
                response.put("errorExportFile", fileName);
                response.put("errorExportUrl", errorFileUrl);
                logger.info("通过 writeErrorsCsv 生成错误文件: {}", fileName);
            } catch (Exception ex) {
                logger.warn("生成错误导出文件失败: {}", ex.getMessage());
                // 即使生成失败，也尝试查找文件
                errorFileUrl = findLatestErrorFile(null);
                if (errorFileUrl != null) {
                    response.put("errorExportUrl", errorFileUrl);
                    logger.info("生成失败但找到错误文件: {}", errorFileUrl);
                }
            }
            
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (Exception e) {
            logger.error("=== 导入过程中发生异常 ===", e);
            
            // 检查是否是编译错误
            String errorMessage = "导入失败：" + (e.getMessage() != null ? e.getMessage() : "未知错误");
            Throwable cause = e.getCause();
            if (cause != null && cause.getMessage() != null && cause.getMessage().contains("Unresolved compilation problems")) {
                errorMessage = "代码编译错误：" + cause.getMessage() + "。请检查后端代码是否正确编译，特别是 BodyPartScoreMapping 类。";
            } else if (e.getMessage() != null && e.getMessage().contains("Unresolved compilation problems")) {
                errorMessage = "代码编译错误：" + e.getMessage() + "。请检查后端代码是否正确编译。";
            }
            
            // 创建错误信息并写入文件
            List<ValidationErrorDTO> errorList = new ArrayList<>();
            ValidationErrorDTO errorDTO = new ValidationErrorDTO();
            errorDTO.setRow(0);
            errorDTO.setPatientId(0);
            errorDTO.setField("系统异常");
            errorDTO.setValue("");
            errorDTO.setMessage(errorMessage);
            errorList.add(errorDTO);
            
            // 返回前端期望的格式：{code: 500, message: "...", errors: [...]}
            Map<String, Object> response = new HashMap<>();
            response.put("code", 500);
            response.put("message", errorMessage);
            response.put("totalErrorCount", 1);
            response.put("errors", errorList);
            
            // 尝试生成错误文件
            String errorFileUrl = null;
            try {
                String fileName = writeErrorsCsv(errorList);
                errorFileUrl = "/api/file/downloadErrorReport?file=" + fileName;
                response.put("errorExportFile", fileName);
                response.put("errorExportUrl", errorFileUrl);
                logger.info("通过 writeErrorsCsv 生成错误文件: {}", fileName);
            } catch (Exception ex) {
                logger.warn("生成错误导出文件失败: {}", ex.getMessage());
                // 即使生成失败，也尝试查找文件
                errorFileUrl = findLatestErrorFile(null);
                if (errorFileUrl != null) {
                    response.put("errorExportUrl", errorFileUrl);
                    logger.info("生成失败但找到错误文件: {}", errorFileUrl);
                }
            }
            
            return new ResponseEntity<>(response, HttpStatus.OK);
        }
    }

    @GetMapping("/downloadErrorReport")
    public ResponseEntity<Resource> downloadErrorReport(@RequestParam("file") String fileName) {
        try {
            logger.info("开始下载错误报告文件: {}", fileName);
            Path filePath = null;
            
            // 支持多个路径查找（相对路径和绝对路径，生产环境可能是绝对路径）
            String[] possiblePaths = {
                "temp-imports/errors",
                "/www/server/healthineers/temp-imports/errors",
                System.getProperty("user.dir") + "/temp-imports/errors"
            };
            
            // 先尝试从错误文件目录读取（可能在子目录中，如 uuid/文件名.csv）
            for (String pathStr : possiblePaths) {
                Path errorDir = Paths.get(pathStr);
                if (Files.exists(errorDir)) {
                    try {
                        logger.debug("在目录 {} 中查找文件: {}", errorDir, fileName);
                        // 递归查找，深度设为3（temp-imports/errors/uuid/文件名.csv）
                        java.util.Optional<Path> foundPath = Files.walk(errorDir, 3)
                            .filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().equals(fileName))
                            .findFirst();
                        if (foundPath.isPresent()) {
                            filePath = foundPath.get();
                            logger.info("在目录 {} 中找到错误文件: {}", errorDir, filePath);
                            break;
                        }
                    } catch (IOException e) {
                        logger.warn("在目录 {} 中搜索错误文件失败: {}", errorDir, e.getMessage());
                    }
                }
            }
            
            // 如果没找到，尝试从旧的错误导出目录读取（兼容旧逻辑）
            if (filePath == null || !Files.exists(filePath)) {
            Path exportDir = Paths.get(UPLOAD_DIR, "error-exports");
                Path oldFilePath = exportDir.resolve(fileName);
                if (Files.exists(oldFilePath)) {
                    filePath = oldFilePath;
                    logger.info("在旧目录中找到错误文件: {}", filePath);
                }
            }
            
            if (filePath == null || !Files.exists(filePath)) {
                logger.warn("错误文件不存在: {}，已尝试的路径: {}", fileName, java.util.Arrays.toString(possiblePaths));
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            
            FileSystemResource resource = new FileSystemResource(filePath.toFile());
            HttpHeaders headers = new HttpHeaders();
            // 使用UTF-8编码的文件名，避免中文文件名乱码
            String encodedFileName = new String(fileName.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFileName + "\"");
            headers.add(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8");
            
            logger.info("成功准备下载错误报告文件: {}, 文件大小: {} bytes", filePath, Files.size(filePath));
            
            return ResponseEntity.ok()
                .headers(headers)
                .body(resource);
        } catch (Exception e) {
            logger.error("下载错误报告失败: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 查找最新的错误文件并返回URL
     * @param importId 导入ID（可选，用于精确查找）
     * @return 错误文件URL，如果找不到返回null
     */
    private String findLatestErrorFile(String importId) {
        try {
            // 支持相对路径和绝对路径（生产环境可能是绝对路径）
            Path errorDir = null;
            String[] possiblePaths = {
                "temp-imports/errors",
                "/www/server/healthineers/temp-imports/errors",
                System.getProperty("user.dir") + "/temp-imports/errors"
            };
            
            for (String pathStr : possiblePaths) {
                Path testPath = Paths.get(pathStr);
                if (Files.exists(testPath)) {
                    errorDir = testPath;
                    logger.info("找到错误文件目录: {}", errorDir);
                    break;
                }
            }
            
            if (errorDir == null || !Files.exists(errorDir)) {
                logger.warn("错误文件目录不存在，尝试过的路径: {}", java.util.Arrays.toString(possiblePaths));
                return null;
            }
            
            // 如果提供了 importId，优先查找该目录下的文件
            if (importId != null && !importId.isEmpty()) {
                Path importDir = errorDir.resolve(importId);
                if (Files.exists(importDir)) {
                    try {
                        List<Path> files = Files.list(importDir)
                            .filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".csv"))
                            .sorted((p1, p2) -> {
                                try {
                                    return Files.getLastModifiedTime(p2).compareTo(Files.getLastModifiedTime(p1));
                                } catch (IOException e) {
                                    return 0;
                                }
                            })
                            .limit(1)
                            .collect(Collectors.toList());
                        
                        if (!files.isEmpty()) {
                            String fileName = files.get(0).getFileName().toString();
                            logger.info("找到错误文件（按importId）: {}", fileName);
                            return "/api/file/downloadErrorReport?file=" + fileName;
                        }
                    } catch (IOException e) {
                        logger.warn("查找错误文件失败（按importId）: {}", e.getMessage());
                    }
                }
            }
            
            // 否则查找所有子目录中最新的文件
            try {
                List<Path> errorFiles = Files.walk(errorDir, 3)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".csv"))
                    .sorted((p1, p2) -> {
                        try {
                            return Files.getLastModifiedTime(p2).compareTo(Files.getLastModifiedTime(p1));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .limit(1)
                    .collect(java.util.stream.Collectors.toList());
                
                if (!errorFiles.isEmpty()) {
                    String fileName = errorFiles.get(0).getFileName().toString();
                    logger.info("找到错误文件（最新）: {}", fileName);
                    return "/api/file/downloadErrorReport?file=" + fileName;
                }
            } catch (IOException e) {
                logger.warn("查找错误文件失败（最新）: {}", e.getMessage());
            }
            
            logger.warn("未找到任何错误文件");
            return null;
        } catch (Exception e) {
            logger.error("查找错误文件失败", e);
            return null;
        }
    }
    
    /**
     * 保存上传的文件到服务器
     * @param file 上传的文件
     * @return 保存后的文件路径
     * @throws IOException
     */
    private String saveUploadedFile(MultipartFile file) throws IOException {
        // 创建上传目录
        Path uploadPath = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        
        // 生成唯一文件名
        String originalFilename = file.getOriginalFilename();
        String fileExtension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String uniqueFileName = UUID.randomUUID().toString() + fileExtension;
        
        // 保存文件
        Path filePath = uploadPath.resolve(uniqueFileName);
        Files.copy(file.getInputStream(), filePath);
        
        return filePath.toString();
    }

    /**
     * 将错误列表写入CSV文件，返回生成的文件名
     */
    private String writeErrorsCsv(List<ValidationErrorDTO> errors) throws IOException {
        Path exportDir = Paths.get(UPLOAD_DIR, "error-exports");
        if (!Files.exists(exportDir)) {
            Files.createDirectories(exportDir);
        }
        String fileName = "import-errors-" + System.currentTimeMillis() + ".csv";
        Path csvPath = exportDir.resolve(fileName);
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
        return fileName;
    }

    private String safeCsv(String s) {
        if (s == null) return "";
        return s.replace("\r", " ").replace("\n", " ").trim();
    }

    private String quote(String s) {
        if (s == null) return "\"\"";
        // 如果包含逗号或引号，进行转义并包裹引号
        boolean needQuote = s.contains(",") || s.contains("\"");
        String escaped = s.replace("\"", "\"\"");
        return needQuote ? "\"" + escaped + "\"" : escaped;
    }
    
    /**
     * 删除文件
     * @param filePath 文件路径
     */
    private void deleteFile(String filePath) {
        try {
            Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException e) {
            System.err.println("删除文件失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取上传文件列表
     */
    @GetMapping("/list")
    public Result getUploadedFiles() {
        try {
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                return Result.ok(new File[0]);
            }
            
            File[] files = uploadPath.toFile().listFiles();
            return Result.ok(files != null ? files : new File[0]);
        } catch (Exception e) {
            return Result.fail("获取文件列表失败：" + e.getMessage());
        }
    }
}
