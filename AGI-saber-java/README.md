# AGI Assistant - Java 版本
# 从 Go 版本的 1:1 翻译

## 📋 项目概述

这是一个从 Go 翻译为 Java（Spring Boot）的**全栈 AI Agent 系统**。

这是一个生产级的 AI 助手，集成了以下功能：
- **阶段一**：LLM 聊天（多轮对话）
- **阶段二**：RAG（检索增强生成）
- **阶段三**：工具 Agent（工具调用）
- **阶段四**：ReAct（推理 + 执行循环）
- **阶段五**：三层记忆系统
- **阶段六**：Harness（带重试的稳定执行）

## 🏗️ 项目架构

```
src/main/java/com/agi/assitant/
├── config/              # 配置类 (ApiConfig.java)
├── controller/          # REST API 控制器
├── service/
│   ├── agent/         # UnifiedAgent（核心调度器）
│   ├── llm/          # LLM 客户端（OpenAI 兼容）
│   ├── rag/          # RAG 引擎（文本切分、向量存储）
│   ├── tools/        # 工具定义与执行
│   ├── memory/       # 三层记忆（短期/长期/偏好）
│   └── infra/        # 基础设施（PostgreSQL、ES、Milvus、Kafka）
├── model/              # 数据模型（Message, Tool, Memory 等）
└── dto/                # 数据传输对象
```

## 🚀 快速开始

### 环境要求
- JDK 17+
- Maven 3.6+
- （可选）PostgreSQL 12+（用于持久化）
- （可选）Milvus、Elasticsearch、Kafka

### 配置

编辑 `src/main/resources/application.yml`：

```yaml
llm:
  api-url: https://your-llm-api-endpoint/v1/chat/completions
  api-key: "your-api-key"
  model: "your-model-name"
  temperature: 0.7
```

### 构建 & 运行

```bash
# 构建
mvn clean package

# 运行
java -jar target/agi-assistant-1.0.0.jar

# 或直接使用 Spring Boot Maven 插件运行
mvn spring-boot:run
```

### 访问

- **Web 界面**：http://localhost:8090
- **API 根路径**：http://localhost:8090/api

## 🌐 API 接口

| 接口 | 方法 | 说明 |
|----------|--------|-------------|
| `/api/chat` | POST | 统一对话入口（自动/显式路由） |
| `/api/upload` | POST | 上传文档到 RAG 知识库 |
| `/api/memory` | GET | 查看三层记忆状态 |
| `/api/tools` | GET | 列出所有可用工具 |
| `/api/tools/mcp` | POST | 动态注册 MCP 工具 |
| `/api/snapshots` | GET | 查看任务执行快照 |
| `/api/status` | GET | 系统状态与配置摘要 |

### 示例：对话请求

```bash
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "现在几点？北京天气怎么样？",
    "use_rag": false,
    "selected_tools": ["get_time", "get_weather"],
    "explicit": true
  }'
```

## 🎯 功能特性

### 1. 多模式路由
- **自动模式**：根据问题自动选择最佳策略
- **显式模式**：前端控制工具选择和 RAG 使用

### 2. ReAct + Harness
- **Planner LLM**：决定调用哪些工具
- **Executor**：带重试逻辑执行工具
- **Generator LLM**：综合观察结果生成最终答案

### 3. RAG（知识库）
- 带重叠的文本切分
- TF（词频）向量化
- 余弦相似度搜索
- LLM 驱动的回答合成

### 4. 三层记忆系统
- **短期记忆**：最近 N 轮滑动窗口
- **长期记忆**：语义召回（embedding 或 TF 降级）
- **偏好记忆**：自动提取的用户偏好（LLM 或规则）

### 5. 工具系统
- 内置工具：`get_time`、`get_weather`、`search_web`、`rag_search`
- 支持 MCP（Model Context Protocol）工具
- 支持动态工具注册

## 🔧 配置说明

查看 `src/main/resources/application.yml` 了解所有配置选项：

```yaml
# 关键配置项
llm:                # LLM API 设置
embedding:          # Embedding API 设置
milvus:             # 向量数据库
postgres:           # PostgreSQL
elasticsearch:       # Elasticsearch
kafka:              # Kafka
rag:                # RAG 参数
memory:             # 记忆设置
harness:            # 重试和超时设置
search:             # Tavily 搜索 API（可选）
```

## 📦 依赖项

- **Spring Boot 3.2.1** - Web 框架
- **OkHttp3** - HTTP 客户端（用于调用 LLM API）
- **PostgreSQL JDBC** - 数据库连接
- **Jackson** - JSON 处理
- **SnakeYAML** - YAML 解析
- **Lombok**（可选）- 减少样板代码

## 🔄 从 Go 版本迁移

本 Java 版本是 Go 版本（`AGI-assistant/final`）的 **1:1 翻译**：

| Go 源文件 | Java 目标文件 |
|----|------|
| `config/config.go` | `config/ApiConfig.java` |
| `internal/llm/llm.go` | `service/llm/LlmClient.java` |
| `internal/rag/rag.go` | `service/rag/RagEngine.java` |
| `internal/tools/tools.go` | `service/tools/ToolsService.java` |
| `internal/memory/memory.go` | `service/memory/*Memory.java` |
| `internal/agent/agent.go` | `service/agent/UnifiedAgent.java` |
| `internal/handler/handler.go` | `controller/ApiController.java` |
| `internal/infra/infra.go` | `service/infra/InfrastructureService.java` |

## 📝 许可证

MIT 许可证

## 🙏 致谢

Go 原版作者。Java 版本为教育和参考目的而翻译。
