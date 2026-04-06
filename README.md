# 创伤可视化系统 — 后端

同济医院创伤急救数据可视化平台后端项目，基于 Spring Boot 2.3，处理 2151 条真实急救抢救室病历数据，提供 RESTful API 支撑前端可视化、统计分析、数据导入、预测功能等。

---

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 2.3.12.RELEASE | 主框架 |
| MyBatis-Plus | 3.4.3 | ORM（含分页插件） |
| MySQL | 5.x | 关系型数据库（9张业务表） |
| Redis + Redisson | 3.13.6 | 缓存 & 分布式锁 |
| RabbitMQ (AMQP) | — | 消息队列 |
| Apache POI | 5.2.3 | Excel 解析（数据导入） |
| Hutool | 5.7.17 | 工具库 |
| Lombok | — | 代码简化 |
| 腾讯位置服务 WebService API | — | 后端地理编码（地址→经纬度） |

> **注意：** 配置类名为 `AmapConfig`（历史遗留命名），实际调用的是腾讯地图 API（`apis.map.qq.com`），密钥配置项为 `amap.api.key`。

---

## 数据库设计（9张表）

| 表名 | 实体类 | 说明 |
|------|--------|------|
| `patient` | Patient | 患者基础信息（性别、年龄、身高、体重、是否绿色通道） |
| `injuryrecord` | InjuryRecord | 病例发生记录（接诊时间、季节、时间段、来院方式、创伤地点经纬度、伤因） |
| `interventiontime` | InterventionTime | 干预方式时间记录（外周、深静脉、骨通道、气管插管、心肺复苏、输血、离室、死亡等） |
| `intervention_extra` | InterventionExtra | 干预补充信息 |
| `gcs_score` | GcsScore | GCS 评分（睁眼/言语/运动三分项 + 总分 + 意识状态） |
| `rts_score` | RtsScore | RTS 评分（GCS/收缩压/呼吸频率三分项） |
| `iss_patient_injury_severity` | IssInjury | ISS 损伤严重度（头颈/面/胸/腹/四肢/体表六部位等级 + ISS总分 + 详情） |
| `patient_info_on_admission` | PatientInfoOnAdmission | 入室生命体征信息 |
| `patient_info_off_admission` | PatientInfoOffAdmission | 离室信息 |

---

## 项目结构

```
src/main/java/com/demo/
├── HealthineersVisualizationApplication.java  # 启动类（@EnableAsync）
├── config/
│   ├── AmapConfig.java           # 腾讯地图 API Key 配置（历史命名遗留）
│   ├── AuthInterceptor.java      # Session 认证拦截器（/api/auth/* 白名单）
│   ├── CorsConfig.java           # 跨域配置
│   ├── FileUploadConfig.java     # 文件上传配置（单文件 5MB，请求 50MB）
│   ├── GlobalExceptionHandler.java  # 全局异常处理
│   ├── MybatisConfig.java        # MyBatis-Plus 分页插件配置
│   └── WebConfig.java            # 注册拦截器
├── controller/                   # RESTful 接口层
│   ├── AuthController.java       # 认证（登录/登出/状态检查）
│   ├── PatientController.java    # 患者信息（列表/分页/查询）
│   ├── PatientStatisticsController.java  # 患者统计（摘要卡片/热力矩阵）
│   ├── InjuryRecordController.java       # 病例记录（地图热力图/时段统计）
│   ├── InterventionTimeController.java   # 干预时间线
│   ├── GcsScoreController.java           # GCS 评分查询
│   ├── RtsScoreController.java           # RTS 评分查询
│   ├── IssInjuryController.java          # ISS 损伤查询
│   ├── PatientInjuryDetailController.java # 伤情详情
│   ├── PatientInfoOnAdmissionController.java  # 入室信息
│   ├── PatientInfoOffAdmissionController.java # 离室信息
│   ├── FileController.java               # 文件操作
│   └── PredictionController.java         # 预测功能代理（转发至 Python 服务）
├── upload/                       # 数据导入模块
│   ├── controller/
│   │   └── DataImportController.java     # 上传 & 导入接口（/api/upload/）
│   ├── service/                          # 各表专属导入服务（9个）
│   ├── validator/                        # 各表字段校验器
│   └── constants/                        # Excel 列名常量
├── entity/                       # 数据库实体类
├── dto/                          # 数据传输对象（统一响应 Result + 各业务 DTO）
├── mapper/                       # MyBatis-Plus Mapper 接口
├── Service/                      # Service 接口 & 实现
└── utils/
    ├── LongitudeLatitudeUtils.java  # 腾讯地图 Geocoding（地址→经纬度，含缓存+重试）
    ├── TimePeriodUtils.java         # 时间段工具（hhmm → 0~5 编号）
    ├── SeasonUtils.java             # 季节工具（月份 → 0~3 编号）
    ├── ExcelImportUtil.java         # Excel 解析工具（基于 Apache POI）
    └── TimeConversionUtils.java     # 时间格式转换
```

---

## 主要 API 接口

### 认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录（username + password） |
| POST | `/api/auth/logout` | 登出 |
| GET | `/api/auth/status` | 检查当前 Session 状态 |

### 患者

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/patient/list` | 所有患者列表 |
| POST | `/api/patient/page` | 分页查询（支持 ID/性别/年龄范围筛选） |
| GET | `/api/patient/page` | 分页查询（GET 方式） |

### 地图 & 病例记录

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/map/locations` | 热力图地点（筛选：季节/时段/年份） |
| GET | `/api/map/locations/daterange` | 按日期范围查询热力图地点 |
| GET | `/api/map/hourly-statistics` | 时段分组统计 |
| GET | `/api/map/monthly-animation` | 月度动画数据 |

### 统计摘要

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/patient-statistics/statistics` | 统计摘要卡片（总患者/日均/干预时间/死亡） |
| GET | `/api/patient-statistics/monthly-heatmap` | 月度×时间段热力矩阵 |

### 数据导入

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/upload/upload` | 上传 Excel 文件（仅保存，不导入） |
| POST | `/api/upload/import/injury` | 导入创伤病例数据（injuryrecord 表） |
| POST | `/api/upload/import/patient` | 导入患者基础数据 |
| POST | `/api/upload/import/iss` | 导入 ISS 数据 |
| POST | `/api/upload/import/gcs-score` | 导入 GCS 评分 |
| POST | `/api/upload/import/rts-score` | 导入 RTS 评分 |

> 导入创伤病例（`/import/injury`）成功后，后端会**自动异步**将新增记录推送给预测服务做增量训练，无需手动触发。

### 预测功能（代理转发至 Python 服务）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/prediction/predict/comprehensive` | 综合预测（区域+时段+季节） |
| GET | `/api/prediction/predict/cause-by-period` | 时段 → 伤因分布 |
| GET | `/api/prediction/predict/cause-by-district` | 区域 → 伤因分布 |
| GET | `/api/prediction/predict/time-distribution` | 伤因 → 时段分布 |
| GET | `/api/prediction/predict/district-distribution` | 各区域发生量 |
| GET | `/api/prediction/predict/district-profile` | 地区画像 |
| GET | `/api/prediction/predict/district-by-period-cause` | 时段+伤因 → 区域分布 |
| GET | `/api/prediction/model/status` | 模型状态 |
| POST | `/api/prediction/model/sync` | 触发全量训练 |
| POST | `/api/prediction/model/incremental` | 推送新数据做增量训练 |

---

## 数据导入流程

1. 前端上传 Excel 文件（最大 5MB）
2. `DataImportController` 保存到 `uploads/` 目录，返回文件路径
3. 执行导入时，对应 ImportService 先全量校验，验证通过才写库
4. 写库成功后自动计算：时间段（TimePeriodUtils）、季节（SeasonUtils）、经纬度（腾讯地图 Geocoding）
5. **导入新增记录后，自动异步推送给 Python 预测服务做增量训练**
6. 返回成功行数、跳过行数、错误列表（最多 500 条）

---

## 地理编码（Geocoding）

`LongitudeLatitudeUtils` 负责将地址文本转换为经纬度：

- **API：** 腾讯位置服务 WebService（`apis.map.qq.com`）
- **双策略：** 先调用地理编码 API，失败则降级调用地点搜索 API
- **内存缓存：** 避免重复调用相同地址
- **重试：** 最多重试 2 次，请求间隔 0.25 秒限流
- **容错：** 模糊地址无法解析时跳过，不强行可视化

---

## 认证机制

- **方式：** Session-based（无 JWT）
- **账号：** `admin` / `hos123`（硬编码）
- **拦截器：** 所有 `/api/**` 接口校验 Session，`/api/auth/*` 白名单放行
- **未登录：** 返回 HTTP 401

---

## 配置说明

| 文件 | 用途 |
|------|------|
| `application.yml` | 通用配置（端口 9090、MyBatis、腾讯地图 Key、文件上传大小限制、预测服务地址） |
| `application-dev.yml` | 本地开发环境（MySQL、Redis 本地连接） |
| `application-prod.yml` | 生产环境（服务器 MySQL、Redis 连接） |

> 当前激活环境：`prod`（可在 `application.yml` 中修改 `spring.profiles.active`）

预测服务地址配置项：

```yaml
prediction:
  service:
    url: http://localhost:8000   # Python 预测服务地址
```

---

## 时间与季节分段

### 时间段（TimePeriodUtils.java）

| 编号 | 名称 | 时间范围 |
|------|------|---------|
| 0 | 夜间 | 00:00–07:59 |
| 1 | 早高峰 | 08:00–09:59 |
| 2 | 午高峰 | 10:00–11:59 |
| 3 | 下午 | 12:00–16:59 |
| 4 | 晚高峰 | 17:00–19:59 |
| 5 | 晚上 | 20:00–23:59 |

### 季节（SeasonUtils.java）

| 编号 | 名称 | 月份 |
|------|------|------|
| 0 | 春季 | 3–5月 |
| 1 | 夏季 | 6–9月 |
| 2 | 秋季 | 10–12月 |
| 3 | 冬季 | 1–2月 |

---

## 本地启动

```bash
# 1. 启动 MySQL 和 Redis
# 2. 修改 application-dev.yml 数据库连接信息
# 3. 切换环境（application.yml → spring.profiles.active: dev）

mvn spring-boot:run
# 服务启动在 http://localhost:9090
```

---

## 相关仓库

- 前端：[tongji-hospital-front](https://github.com/hfutwz/tongji-hospital-front)
- 预测服务：[Healthineers-visualization-predict](https://github.com/hfutwz/Healthineers-visualization-predict)
