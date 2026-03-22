package com.demo.controller;

import com.demo.dto.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * 预测服务代理 Controller
 *
 * 职责：将前端请求转发到独立的 Python 预测微服务（时空因导算法）。
 * 本 Controller 不含任何业务判断，只做透明代理。
 *
 * 对应 Python 服务: Healthineers-visualization-predict (端口 8000)
 */
@RestController
@RequestMapping("/api/prediction")
public class PredictionController {

    private static final Logger log = LoggerFactory.getLogger(PredictionController.class);

    @Value("${prediction.service.url:http://localhost:8000}")
    private String predictionServiceUrl;

    private final RestTemplate restTemplate;

    public PredictionController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 预测接口
    // ─────────────────────────────────────────────────────────────────────

    /**
     * T5: 时段（+可选季节）→ 伤因概率分布
     * GET /api/prediction/cause-by-period?time_period=3&season=2
     */
    @GetMapping("/cause-by-period")
    public Result causeByPeriod(
            @RequestParam("time_period") int timePeriod,
            @RequestParam(value = "season", required = false) Integer season) {
        try {
            StringBuilder url = new StringBuilder(predictionServiceUrl)
                    .append("/predict/cause-by-period?time_period=").append(timePeriod);
            if (season != null) {
                url.append("&season=").append(season);
            }
            Map<?, ?> result = restTemplate.getForObject(url.toString(), Map.class);
            return Result.ok(result);
        } catch (ResourceAccessException e) {
            log.warn("预测服务不可达: {}", e.getMessage());
            return Result.fail("预测服务暂时不可用，请稍后重试");
        } catch (Exception e) {
            log.error("调用预测服务失败", e);
            return Result.fail("预测请求失败: " + e.getMessage());
        }
    }

    /**
     * T2: 季节 → 伤因概率分布
     * GET /api/prediction/cause-by-season?season=0
     */
    @GetMapping("/cause-by-season")
    public Result causeBySeason(@RequestParam int season) {
        try {
            String url = predictionServiceUrl + "/predict/cause-by-season?season=" + season;
            Map<?, ?> result = restTemplate.getForObject(url, Map.class);
            return Result.ok(result);
        } catch (ResourceAccessException e) {
            log.warn("预测服务不可达: {}", e.getMessage());
            return Result.fail("预测服务暂时不可用，请稍后重试");
        } catch (Exception e) {
            log.error("调用预测服务失败", e);
            return Result.fail("预测请求失败: " + e.getMessage());
        }
    }

    /**
     * T4: 地区 → 伤因概率分布
     * GET /api/prediction/cause-by-district?district=宝山区
     */
    @GetMapping("/cause-by-district")
    public Result causeByDistrict(@RequestParam String district) {
        try {
            String encoded = URLEncoder.encode(district, "UTF-8");
            String url = predictionServiceUrl + "/predict/cause-by-district?district=" + encoded;
            Map<?, ?> result = restTemplate.getForObject(url, Map.class);
            return Result.ok(result);
        } catch (UnsupportedEncodingException e) {
            log.error("URL编码失败", e);
            return Result.fail("地区参数编码失败");
        } catch (ResourceAccessException e) {
            log.warn("预测服务不可达: {}", e.getMessage());
            return Result.fail("预测服务暂时不可用，请稍后重试");
        } catch (Exception e) {
            log.error("调用预测服务失败", e);
            return Result.fail("预测请求失败: " + e.getMessage());
        }
    }

    /**
     * T1: 综合预测（区域 + 时段 + 季节）
     * POST /api/prediction/comprehensive
     * Body: {"time_period": 3, "season": 2, "district": "宝山区"}
     */
    @PostMapping("/comprehensive")
    public Result comprehensive(@RequestBody Map<String, Object> body) {
        try {
            String url = predictionServiceUrl + "/predict/comprehensive";
            Map<?, ?> result = restTemplate.postForObject(url, body, Map.class);
            return Result.ok(result);
        } catch (ResourceAccessException e) {
            log.warn("预测服务不可达: {}", e.getMessage());
            return Result.fail("预测服务暂时不可用，请稍后重试");
        } catch (Exception e) {
            log.error("调用预测服务失败", e);
            return Result.fail("预测请求失败: " + e.getMessage());
        }
    }

    /**
     * T2: 某伤因的时段/季节历史分布
     * GET /api/prediction/time-distribution?injury_cause=0
     */
    @GetMapping("/time-distribution")
    public Result timeDistribution(@RequestParam("injury_cause") int injuryCause) {
        try {
            String url = predictionServiceUrl + "/predict/time-distribution?injury_cause=" + injuryCause;
            Map<?, ?> result = restTemplate.getForObject(url, Map.class);
            return Result.ok(result);
        } catch (ResourceAccessException e) {
            log.warn("预测服务不可达: {}", e.getMessage());
            return Result.fail("预测服务暂时不可用，请稍后重试");
        } catch (Exception e) {
            log.error("调用预测服务失败", e);
            return Result.fail("预测请求失败: " + e.getMessage());
        }
    }

    /**
     * T3/T6: 各区域伤情分布（热力图数据）
     * GET /api/prediction/district-distribution?injury_cause=0
     */
    @GetMapping("/district-distribution")
    public Result districtDistribution(
            @RequestParam(value = "injury_cause", required = false) Integer injuryCause) {
        try {
            StringBuilder url = new StringBuilder(predictionServiceUrl)
                    .append("/predict/district-distribution");
            if (injuryCause != null) {
                url.append("?injury_cause=").append(injuryCause);
            }
            Map<?, ?> result = restTemplate.getForObject(url.toString(), Map.class);
            return Result.ok(result);
        } catch (ResourceAccessException e) {
            log.warn("预测服务不可达: {}", e.getMessage());
            return Result.fail("预测服务暂时不可用，请稍后重试");
        } catch (Exception e) {
            log.error("调用预测服务失败", e);
            return Result.fail("预测请求失败: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 模型管理接口
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 查看当前模型状态（版本、样本量、评估指标）
     * GET /api/prediction/model/status
     */
    @GetMapping("/model/status")
    public Result modelStatus() {
        try {
            String url = predictionServiceUrl + "/api/model/status";
            Map<?, ?> result = restTemplate.getForObject(url, Map.class);
            return Result.ok(result);
        } catch (ResourceAccessException e) {
            log.warn("预测服务不可达: {}", e.getMessage());
            return Result.fail("预测服务暂时不可用");
        } catch (Exception e) {
            log.error("查询模型状态失败", e);
            return Result.fail("查询失败: " + e.getMessage());
        }
    }

    /**
     * 触发模型增量更新（数据导入完成后由 UploadController 异步调用，也可手动触发）
     * POST /api/prediction/model/trigger-update
     */
    @PostMapping("/model/trigger-update")
    public Result triggerModelUpdate() {
        try {
            String url = predictionServiceUrl + "/api/model/trigger-update";
            Map<?, ?> result = restTemplate.postForObject(url, null, Map.class);
            return Result.ok(result);
        } catch (ResourceAccessException e) {
            log.warn("预测服务不可达，模型更新跳过: {}", e.getMessage());
            // 非阻塞：服务不可达时返回成功（不影响主流程）
            Map<String, String> skipResult = new HashMap<>();
            skipResult.put("status", "skipped");
            skipResult.put("reason", "prediction_service_unavailable");
            return Result.ok(skipResult);
        } catch (Exception e) {
            log.error("触发模型更新失败", e);
            return Result.fail("模型更新失败: " + e.getMessage());
        }
    }

    /**
     * 历史模型版本列表
     * GET /api/prediction/model/history
     */
    @GetMapping("/model/history")
    public Result modelHistory() {
        try {
            String url = predictionServiceUrl + "/api/model/history";
            Object result = restTemplate.getForObject(url, Object.class);
            return Result.ok(result);
        } catch (ResourceAccessException e) {
            log.warn("预测服务不可达: {}", e.getMessage());
            return Result.fail("预测服务暂时不可用");
        } catch (Exception e) {
            log.error("查询模型历史失败", e);
            return Result.fail("查询失败: " + e.getMessage());
        }
    }
}
