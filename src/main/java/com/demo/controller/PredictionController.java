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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /** 与预测服务 ALLOWED_DISTRICTS、前端 predictionOptions 一致 */
    private static final Set<String> ALLOWED_DISTRICTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "黄浦区", "徐汇区", "长宁区", "静安区", "普陀区", "虹口区", "杨浦区", "浦东新区",
            "闵行区", "宝山区", "嘉定区", "金山区", "松江区", "青浦区", "奉贤区", "崇明区"
    )));

    @Value("${prediction.service.url:http://localhost:8000}")
    private String predictionServiceUrl;

    private final RestTemplate restTemplate;

    public PredictionController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private boolean isAllowedDistrict(String district) {
        if (district == null || district.trim().isEmpty()) {
            // null 或空字符串代表全选，直接放行
            return true;
        }
        return ALLOWED_DISTRICTS.contains(district.trim());
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
        if (!isAllowedDistrict(district)) {
            return Result.fail("地区参数不在允许列表内");
        }
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
        Object dObj = body != null ? body.get("district") : null;
        if (dObj instanceof String && !((String) dObj).trim().isEmpty()) {
            if (!isAllowedDistrict((String) dObj)) {
                return Result.fail("地区参数不在允许列表内");
            }
        }
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
     * injury_cause 可不传（全选，汇总所有伤因）
     */
    @GetMapping("/time-distribution")
    public Result timeDistribution(
            @RequestParam(value = "injury_cause", required = false) Integer injuryCause) {
        try {
            StringBuilder url = new StringBuilder(predictionServiceUrl)
                    .append("/predict/time-distribution");
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

    /**
     * 类型 3：某地区 → 时段 / 季节 / 伤因分布
     * GET /api/prediction/district-profile?district=宝山区
     * district 可不传（全选，汇总全市数据）
     */
    @GetMapping("/district-profile")
    public Result districtProfile(
            @RequestParam(value = "district", required = false) String district) {
        if (!isAllowedDistrict(district)) {
            return Result.fail("地区参数不在允许列表内");
        }
        try {
            StringBuilder url = new StringBuilder(predictionServiceUrl)
                    .append("/predict/district-profile");
            if (district != null && !district.trim().isEmpty()) {
                url.append("?district=").append(URLEncoder.encode(district.trim(), "UTF-8"));
            }
            Map<?, ?> result = restTemplate.getForObject(url.toString(), Map.class);
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
     * 类型 4：某时段 + 某伤因 → 各区县分布（原始计数）
     * GET /api/prediction/district-by-period-cause?time_period=3&injury_cause=0
     * time_period、injury_cause 均可不传（全选）
     */
    @GetMapping("/district-by-period-cause")
    public Result districtByPeriodCause(
            @RequestParam(value = "time_period", required = false) Integer timePeriod,
            @RequestParam(value = "injury_cause", required = false) Integer injuryCause) {
        if (timePeriod != null && (timePeriod < 0 || timePeriod > 5)) {
            return Result.fail("时段参数超出允许范围（0-5）");
        }
        if (injuryCause != null && (injuryCause < 0 || injuryCause > 4)) {
            return Result.fail("伤因参数超出允许范围（0-4）");
        }
        try {
            StringBuilder url = new StringBuilder(predictionServiceUrl)
                    .append("/predict/district-by-period-cause");
            boolean hasParam = false;
            if (timePeriod != null) {
                url.append("?time_period=").append(timePeriod);
                hasParam = true;
            }
            if (injuryCause != null) {
                url.append(hasParam ? "&" : "?").append("injury_cause=").append(injuryCause);
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
     * 获取模型版本详细信息（转发到 status 接口）
     * GET /api/prediction/model/version
     */
    @GetMapping("/model/version")
    public Result modelVersion() {
        try {
            // Python 服务无独立 /version 端点，复用 /api/model/status
            String url = predictionServiceUrl + "/api/model/status";
            Map<?, ?> result = restTemplate.getForObject(url, Map.class);
            return Result.ok(result);
        } catch (ResourceAccessException e) {
            log.warn("预测服务不可达: {}", e.getMessage());
            return Result.fail("预测服务暂时不可用");
        } catch (Exception e) {
            log.error("查询模型版本失败", e);
            return Result.fail("查询失败: " + e.getMessage());
        }
    }

    /**
     * 同步预测模型（手动触发全量训练）
     * POST /api/prediction/model/sync
     *
     * 供前端"模型训练"按钮的"全量训练"调用。
     * 对应 Python 服务: POST /api/model/train
     */
    @PostMapping("/model/sync")
    public Result syncModel(@RequestBody(required = false) Map<String, Object> body) {
        try {
            String url = predictionServiceUrl + "/api/model/train";
            Map<?, ?> result = restTemplate.postForObject(url, body, Map.class);
            if (result == null) {
                return Result.fail("预测服务返回空结果");
            }
            return Result.ok(result);
        } catch (ResourceAccessException e) {
            log.warn("预测服务不可达，模型同步失败: {}", e.getMessage());
            return Result.fail("预测服务暂时不可用，请检查 Python 服务是否启动");
        } catch (Exception e) {
            log.error("模型同步失败", e);
            return Result.fail("模型同步失败: " + e.getMessage());
        }
    }

    /**
     * 触发模型增量更新（旧接口保留，需 Python 可达数据库时使用）
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
     * 推送新增数据给预测服务做增量训练（不依赖数据库）
     * POST /api/prediction/model/incremental
     *
     * 由 InjuryRecordImportService 导入成功后异步调用，
     * body 格式：{"records": [{admission_date, admission_time, time_period, season,
     *                          injury_cause_category, injury_location}, ...]}
     */
    @PostMapping("/model/incremental")
    public Result incrementalPush(@RequestBody Map<String, Object> body) {
        try {
            String url = predictionServiceUrl + "/api/model/incremental";
            Map<?, ?> result = restTemplate.postForObject(url, body, Map.class);
            return Result.ok(result);
        } catch (ResourceAccessException e) {
            log.warn("预测服务不可达，增量推送跳过: {}", e.getMessage());
            Map<String, String> skipResult = new HashMap<>();
            skipResult.put("status", "skipped");
            skipResult.put("reason", "prediction_service_unavailable");
            return Result.ok(skipResult);
        } catch (Exception e) {
            log.error("增量推送失败", e);
            return Result.fail("增量推送失败: " + e.getMessage());
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
