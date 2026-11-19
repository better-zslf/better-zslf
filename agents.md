# Agent 模型规则

- 创建 Team 成员时，使用并跟随 Lead 当前模型，不单独覆盖模型。
- 创建 subagent 时，使用和 Lead 相同 Provider 提供的 `deepseek-v4.1-flash`，推理档位设为 `max`。
