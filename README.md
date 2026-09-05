# WealthPilot · 资产规划师 Agent

对话式个人资产规划系统：用户用自然语言描述资产状况，Agent 多轮对话采集信息、确认风险偏好，由**确定性金融计算引擎**生成未来几年收益曲线（三情景 + 蒙特卡洛模拟），前端实时展示 ECharts 曲线。

## 核心架构

```
用户对话 → GLM (LLM) 理解与抽取 → Tool Calling 调用 Java 工具
        → FinanceService 确定性计算（复利/配置/蒙特卡洛1000次）
        → 结构化结果 → LLM 翻译成人话 + 前端图表
```

**关键设计：LLM 不碰数字**——所有金额计算由代码完成，大模型只负责意图理解和表达，从架构上避免幻觉产生财务数字。

## 技术栈

- 后端：Spring Boot 3 + **LangChain4j**（Tool Calling + 对话记忆 + @ToolMemoryId 会话隔离）
- 大模型：智谱 GLM-4-Flash（OpenAI 兼容接口）
- 前端：Vue 3 + ECharts（对话 + 资产档案面板 + 收益曲线）
- 金融模型：三情景推演（期望±波动率）+ 蒙特卡洛模拟（年化收益率正态分布，1000 次路径）

## 启动

```bash
export GLM_API_KEY=你的智谱APIKey
mvn clean package -DskipTests
java -jar target/wealth-pilot-1.0.0.jar   # 后端 :8090

cd ../wealth-pilot-web && npm i && npm run dev   # 前端 :8083
```

## 对话示例

> 我28岁，有10万现金、5万基金，月薪8000，月花3000，目标5年后攒够买房首付
> → Agent 追问确认 → 风险偏好评估 → 生成配置建议（股/债/现金比例）+ 10年三情景曲线 + 蒙特卡洛置信区间
